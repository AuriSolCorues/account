/**
 * 职责：通行密钥登录确认页（Get 流程的透明浮层）——系统凭据管理器从 PublicKeyCredentialEntry
 *       卡片拉起，职责链：retrieveProviderGetCredentialRequest（拿 requestJson + 系统下发的
 *       clientDataHash）→ BiometricPrompt（CryptoObject=cipher 解封 DEK）→ vault 读私钥
 *       （PasskeyVaultAccess）→ 组包（签名针对浏览器组包的 clientDataHash）→
 *       signCount 回写 vault → 回包断言 JSON。
 * 架构位置：service/ 系统入口组件（manifest 透明浮层）；算法在 WebAuthn.kt、密钥工具在
 *           PasskeyKeys.kt、库访问在 PasskeyVaultAccess、索引在 PasskeyRegistry。
 * 安全设计：cipher CryptoObject（biometricKey 用户认证门槛）解封 DEK，未过指纹解不出私钥；
 *           私钥只在签名作用域内明文存活，DEK 用完即 fill(0)，回包后无驻留。
 * Python 类比：≈ webhook 确认处理器——收参数、验证、组装响应、回状态码。
 */
package com.copy.account.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import com.copy.account.data.config.ALLOW_SCREENSHOTS_SETTING
import com.copy.account.data.config.settingsDataStore
import com.copy.account.security.PasskeyIntegrity
import com.copy.account.security.PasskeyRegistry
import com.copy.account.security.PasskeyVaultAccess
import com.copy.account.security.SecureVaultStore
import com.copy.account.security.WebAuthnRequest
import com.copy.account.security.base64UrlDecode
import com.copy.account.security.es256AdaptRaw
import com.copy.account.security.parseWebAuthnRequest
import com.copy.account.security.patchSignCount
import com.copy.account.security.softwareSigner
import androidx.credentials.webauthn.AuthenticatorAssertionResponse
import androidx.credentials.webauthn.FidoPublicKeyCredential
import androidx.credentials.webauthn.PublicKeyCredentialRequestOptions
import com.copy.account.ui.components.AppBottomSheet
import com.copy.account.ui.components.OverlayThemeHost
import com.copy.account.ui.components.PasskeyPasswordSheetItems
import javax.crypto.Cipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** 登录确认页。任何失败路径都 setResult(RESULT_CANCELED)，系统放弃本次断言。 */
class PasskeyGetActivity : FragmentActivity() {

    private val registry by lazy { PasskeyRegistry(this) }
    private val vaultStore by lazy { SecureVaultStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotFlag()
        setupTransparentWindow()
        // 完整请求经系统 API 取（requestJson + 系统下发的 clientDataHash——签名基准）
        val providerRequest = runCatching {
            PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        }.getOrNull()
        val option = providerRequest?.credentialOptions
            ?.filterIsInstance<androidx.credentials.GetPublicKeyCredentialOption>()
            ?.firstOrNull()
        val request = option?.requestJson?.let { parseWebAuthnRequest(it) }
        val clientDataHash = option?.clientDataHash
        if (providerRequest == null || option == null || request == null || clientDataHash == null) {
            finishCanceled()
            return
        }
        // 完整性闸门：extras 里没有本应用 Service 加的 HMAC 标签（第三方伪造 intent 拉起）
        // 即静默取消——防断言钓鱼（详见 PasskeyIntegrity）。requestJson 与标签来自同一
        // intent，标签对不上即参数被篡改/伪造。
        if (!PasskeyIntegrity.verify(option.requestJson, intent.getByteArrayExtra(PasskeyProviderService.EXTRA_INTEGRITY))) {
            finishCanceled()
            return
        }
        // 出卡（系统凭据卡）已是用户确认：直接弹系统验证框，无中间确认层。
        beginAssertion(request, clientDataHash)
    }

    /**
     * 直接弹系统验证框（无自家 UI）：认证器组合 BIOMETRIC_STRONG|DEVICE_CREDENTIAL——
     * 有指纹弹系统指纹框，无指纹弹系统锁屏密码/PIN 框，两者同一套系统 UI。
     * （passkey 功能本身要求 API≥34，组合+CryptoObject 的版本约束不落在支持设备上。）
     */
    private fun beginAssertion(request: WebAuthnRequest, clientDataHash: ByteArray) {
        val cipher = runCatching { vaultStore.beginBiometricDecrypt() }.getOrNull()
        if (cipher == null) {
            // 无 biometric 包装（未启用生物识别/密钥被清）：锁定态无法解封，放弃
            Toast.makeText(this, "请先在本应用中启用并使用一次生物识别解锁", Toast.LENGTH_LONG).show()
            finishCanceled()
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val biometric =
                        result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC
                    completeAssertion(request, clientDataHash, result.cryptoObject?.cipher, biometric)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    finishCanceled()
                }
            }
        )
        // 首选「强生物识别或锁屏凭据」（照 AutofillAuthActivity 先例）；个别 API<30 机型
        // 不接受该组合（构造即抛），退回仅强生物识别——passkey 设备均 ≥34，组合常态生效。
        val combined = BiometricPrompt.PromptInfo.Builder()
            .setTitle("使用通行密钥登录")
            .setSubtitle(request.rpId)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        val strongOnly = BiometricPrompt.PromptInfo.Builder()
            .setTitle("使用通行密钥登录")
            .setSubtitle(request.rpId)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("取消")
            .build()
        try {
            prompt.authenticate(combined, BiometricPrompt.CryptoObject(cipher))
        } catch (_: IllegalArgumentException) {
            prompt.authenticate(strongOnly, BiometricPrompt.CryptoObject(cipher))
        }
    }

    /**
     * 认证成功后按认证类型取 DEK，随后同一条签名链路。
     * 指纹态：cryptoCipher 解生物识别包装密文；密码/锁屏凭据态：CryptoObject 的 cipher
     * 不被放行，且 DEVICE_CREDENTIAL 通过的是设备锁屏凭据（证明机主），DEK 仍需本应用
     * 主密码派生——落到主密码输入弹层（theme 化，与主界面同视觉）。
     */
    private fun completeAssertion(
        request: WebAuthnRequest,
        clientDataHash: ByteArray,
        cryptoCipher: Cipher?,
        biometric: Boolean
    ) {
        if (biometric && cryptoCipher != null) {
            runCatching {
                cryptoCipher.doFinal(vaultStore.biometricCiphertext() ?: error("无生物识别包装数据"))
            }.onSuccess { dek ->
                signAndReply(request, clientDataHash, dek)
            }.onFailure {
                Toast.makeText(this, "登录失败：${it.message.orEmpty()}", Toast.LENGTH_SHORT).show()
                finishCanceled()
            }
        } else {
            promptPasswordThen(request.rpId) { password ->
                // PBKDF2（30 万轮）计算密集，必须后台线程——主 App 解锁页同规矩；
                // Toast/finish 回主线程（后台线程弹 Toast 会崩）。
                Thread {
                    val result = runCatching {
                        val unlock = vaultStore.unlockWithPassword(password)
                            ?: error("主密码不匹配")
                        // backupKey 是导出用密钥材料，断言用不到：unwrap 成功即清零不留堆
                        //（照 AutofillSelfHeal.unlockAccountWithPassword 先例）。
                        unlock.backupKey.clear()
                        unlock.dataKey
                    }
                    runOnUiThread {
                        result.onSuccess { dek ->
                            signAndReply(request, clientDataHash, dek)
                        }.onFailure {
                            Toast.makeText(this, "登录失败：${it.message.orEmpty()}", Toast.LENGTH_SHORT).show()
                            finishCanceled()
                        }
                    }
                }.start()
            }
        }
    }

    /** 密码态兜底：主密码输入弹层（Compose 主题化），验证通过后回调。 */
    private fun promptPasswordThen(rpId: String, onVerified: (String) -> Unit) {
        setContent {
            OverlayThemeHost {
                AppBottomSheet(onDismiss = { finishCanceled() }) {
                    PasskeyPasswordSheetItems(
                        rpId = rpId,
                        onConfirm = onVerified,
                        onCancel = { finishCanceled() }
                    )
                }
            }
        }
    }

    /** 认证通过后的签名回包链路（DEK 用完即清零；任何失败 Toast+取消，不崩在回调里）。 */
    private fun signAndReply(request: WebAuthnRequest, clientDataHash: ByteArray, dek: ByteArray) {
        try {
            signAndReplyInner(request, clientDataHash, dek)
        } catch (e: Exception) {
            Toast.makeText(this, "登录失败：${e.message.orEmpty()}", Toast.LENGTH_SHORT).show()
            finishCanceled()
        } finally {
            dek.fill(0)
        }
    }

    private fun signAndReplyInner(request: WebAuthnRequest, clientDataHash: ByteArray, dek: ByteArray) {
        val rpId = request.rpId
        val matches = PasskeyVaultAccess.loadPasskeysByRpId(this, dek, rpId)
        if (matches.isEmpty()) {
            Toast.makeText(this, "库内无 $rpId 的通行密钥", Toast.LENGTH_SHORT).show()
            finishCanceled()
            return
        }
        // 多凭据：优先 allowCredentials 提示，其次用户名匹配，再次首条
        val allowIds = request.allowCredentialIds.orEmpty()
        val material = matches.firstOrNull { allowIds.contains(it.credentialId) }
            ?: matches.firstOrNull { request.userName != null && it.userName == request.userName }
            ?: matches.first()
        val nextCount = signCountInVault(dek, material.credentialId) + 1

        // ===== 官方 androidx.credentials.webauthn 组包（替代全部自拼协议）=====
        // 官方 dataToSign() = authData || (clientDataHash ?: sha256(clientJson))——
        // 浏览器流程传系统下发的 clientDataHash，签名基准与浏览器视角字节一致。
        val requestOptions = PublicKeyCredentialRequestOptions(request.requestJson)
        val userHandleBytes = base64UrlDecode(material.userHandle)
        val assertionResponse = AuthenticatorAssertionResponse(
            requestOptions = requestOptions,
            credentialId = base64UrlDecode(material.credentialId),
            origin = "https://" + rpId,
            up = true,
            uv = true,
            be = false,
            bs = false,
            userHandle = userHandleBytes,
            packageName = null,
            clientDataHash = clientDataHash
        )
        // 用库内私钥对官方 dataToSign() 签名，塞回官方响应对象。
        // 官方默认 authData 的 signCount 恒 0，回填 vault 维护的递增计数再签
        //（RP 以计数不增判克隆；注册时为 0，首次登录回 1，此后递增）。
        assertionResponse.authenticatorData =
            patchSignCount(assertionResponse.authenticatorData, nextCount.toInt())
        val signer = softwareSigner(material.privateKeyPkcs8)
        signer.update(assertionResponse.dataToSign())
        assertionResponse.signature = es256AdaptRaw(signer.sign())

        val credential = FidoPublicKeyCredential(
            rawId = base64UrlDecode(material.credentialId),
            response = assertionResponse,
            authenticatorAttachment = "platform"
        )
        val responseJson = credential.json()

        // signCount 持久化（vault 账号段为准；registry 索引同步）
        persistSignCount(dek, material.credentialId, nextCount)
        if (registry.find(material.credentialId) != null) {
            registry.updateSignCount(material.credentialId, nextCount)
        }

        // 系统契约：PendingIntent 的 result Intent 必须经 PendingIntentHandler 打包
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(
            result,
            GetCredentialResponse(PublicKeyCredential(responseJson))
        )
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    /** vault 内该凭据的当前 signCount（跨账号扫描）。 */
    private fun signCountInVault(dek: ByteArray, credentialId: String): Long {
        val state = vaultStore.load(dek) ?: return 0L
        return state.accounts.asSequence()
            .flatMap { it.passkeys.asSequence() }
            .firstOrNull { it.credentialId == credentialId }?.signCount ?: 0L
    }

    /** signCount 写回 vault 账号段。 */
    private fun persistSignCount(dek: ByteArray, credentialId: String, nextCount: Long) {
        val state = vaultStore.load(dek) ?: return
        val accounts = state.accounts.map { acc ->
            if (acc.passkeys.none { it.credentialId == credentialId }) {
                acc
            } else {
                acc.copy(passkeys = acc.passkeys.map {
                    if (it.credentialId == credentialId) it.copy(signCount = nextCount) else it
                })
            }
        }
        vaultStore.save(state.copy(accounts = accounts), dek)
    }

    private fun finishCanceled() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    /** 透明窗口设置（照 AutofillAuthActivity）：底部浮层。 */
    private fun setupTransparentWindow() {
        window.requestFeature(Window.FEATURE_NO_TITLE)
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window.setBackgroundDrawableResource(android.R.color.transparent)
    }

    /**
     * 截图保护跟随主界面「禁止截图」设置（DataStore 键 allow_screenshots 反相）：
     * 浮层含主密码输入，不能比主界面少这层保护。runBlocking 一次读（毫秒级）——
     * 浮层生命周期极短，不值得为此架协程。
     */
    private fun applyScreenshotFlag() {
        val allow = runCatching {
            runBlocking { settingsDataStore.data.first()[ALLOW_SCREENSHOTS_SETTING] ?: false }
        }.getOrDefault(false)
        if (!allow) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
    }
}
