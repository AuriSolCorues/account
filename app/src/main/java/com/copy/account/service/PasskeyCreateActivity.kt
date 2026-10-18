/**
 * 职责：通行密钥注册确认页（Create 流程的透明浮层）——系统凭据管理器从 CreateEntry 卡片
 *       拉起，职责链：retrieveProviderCreateCredentialRequest（拿 requestJson + 系统下发的
 *       clientDataHash）→ 确认卡（网站域 + 用户名）→ BiometricPrompt（CryptoObject=cipher
 *       解封 DEK）→ 软件密钥对生成 → attestation（fmt=none 对齐 KeePassDX）组包 →
 *       私钥写入 Account.passkeys（vault 原子回写）→ registry 索引 → PendingIntentHandler 回包。
 * 架构位置：service/ 系统入口组件；算法在 WebAuthn.kt、密钥工具在 PasskeyKeys.kt、
 *           库写入在 PasskeyVaultAccess.savePasskey。
 * 安全设计：clientDataHash 用系统下发的原始值（浏览器已组包），绝不自拼——自拼字节与
 *           浏览器视角不一致是 RP 拒收注册的根因（KeePassDX 的 ClientDataDefinedResponse
 *           实证：回包 clientDataJSON 浏览器会替换，签名必须对齐浏览器 hash）。
 *           私钥随账号段 AES-GCM 加密落盘，可随 .acc 备份完整恢复。
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
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import com.copy.account.security.COSE_ALG_ES256
import com.copy.account.security.PasskeyIntegrity
import com.copy.account.security.PasskeyRegistry
import com.copy.account.security.PasskeyRegistryEntry
import com.copy.account.security.PasskeyVaultAccess
import com.copy.account.security.SecureVaultStore
import com.copy.account.security.WebAuthnRequest
import com.copy.account.security.attestationAuthData
import com.copy.account.security.base64Url
import com.copy.account.security.base64UrlDecode
import com.copy.account.security.cborEncode
import com.copy.account.security.CborValue
import com.copy.account.security.coseEs256PublicKey
import com.copy.account.security.generateSoftwareEs256KeyPair
import com.copy.account.security.parseWebAuthnRequest
import androidx.credentials.webauthn.AuthenticatorAttestationResponse
import androidx.credentials.webauthn.FidoPublicKeyCredential
import androidx.credentials.webauthn.PublicKeyCredentialCreationOptions
import com.copy.account.ui.components.AppBottomSheet
import com.copy.account.ui.components.OverlayThemeHost
import com.copy.account.ui.components.PasskeyCreateSheetItems
import com.copy.account.data.config.ALLOW_SCREENSHOTS_SETTING
import com.copy.account.data.config.settingsDataStore
import javax.crypto.Cipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 注册确认页。流程失败（解析失败/认证取消/组包异常）一律 setResult(RESULT_CANCELED)，
 * 系统放弃本次创建——绝不在验证失败时半途落盘。
 */
class PasskeyCreateActivity : FragmentActivity() {

    private val registry by lazy { PasskeyRegistry(this) }
    private val vaultStore by lazy { SecureVaultStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotFlag()
        setupTransparentWindow()
        // 完整请求经系统 API 取：requestJson + 系统下发的 clientDataHash（关键！）
        val providerRequest = runCatching {
            PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        }.getOrNull()
        val pkRequest = providerRequest?.callingRequest
            as? androidx.credentials.CreatePublicKeyCredentialRequest
        val request = pkRequest?.requestJson?.let { parseWebAuthnRequest(it) }
        val clientDataHash = pkRequest?.clientDataHash
        if (pkRequest == null || request == null || clientDataHash == null) {
            finishCanceled()
            return
        }
        // 完整性闸门（同 PasskeyGetActivity）：伪造拉起静默取消。
        if (!PasskeyIntegrity.verify(pkRequest.requestJson, intent.getByteArrayExtra(PasskeyProviderService.EXTRA_INTEGRITY))) {
            finishCanceled()
            return
        }
        showConfirmCard(request, clientDataHash)
    }

    /** Compose 底部弹层（主界面 AppBottomSheet 同款）：内容组件在 ui/components（可 IDE 预览）。 */
    private fun showConfirmCard(request: WebAuthnRequest, clientDataHash: ByteArray) {
        setContent {
            OverlayThemeHost {
                AppBottomSheet(onDismiss = { finishCanceled() }) {
                    PasskeyCreateSheetItems(
                        rpId = request.rpId,
                        userName = request.userName.orEmpty(),
                        onConfirm = { beginCreation(request, clientDataHash) },
                        onCancel = { finishCanceled() }
                    )
                }
            }
        }
    }

    /** 弹 BiometricPrompt（CryptoObject=cipher）；通过后解封 DEK 完成注册。 */
    private fun beginCreation(request: WebAuthnRequest, clientDataHash: ByteArray) {
        val cipher = runCatching { vaultStore.beginBiometricDecrypt() }.getOrNull()
        if (cipher == null) {
            Toast.makeText(this, "请先在本应用中启用并使用一次生物识别解锁", Toast.LENGTH_LONG).show()
            finishCanceled()
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    completeCreation(request, clientDataHash, result.cryptoObject?.cipher ?: cipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    finishCanceled()
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("通行密钥验证")
                .setSubtitle(request.rpId)
                .setNegativeButtonText("取消")
                .build(),
            BiometricPrompt.CryptoObject(cipher)
        )
    }

    /** 认证成功后：解 DEK → 密钥对 → attestation 组包（官方类）→ 私钥落账号段 → 索引 → 回包。 */
    private fun completeCreation(
        request: WebAuthnRequest,
        clientDataHash: ByteArray,
        cipher: Cipher
    ) {
        runCatching {
            val dek = cipher.doFinal(vaultStore.biometricCiphertext() ?: error("无生物识别包装数据"))
            try {
                val credentialId = PasskeyRegistry.newCredentialId()
                val credentialIdBytes = base64UrlDecode(credentialId)
                val (privateB64, publicB64, publicDerB64) = generateSoftwareEs256KeyPair()

                val userHandle = request.userIdBase64Url
                    ?.let { runCatching { base64UrlDecode(it) }.getOrNull() }
                    ?: base64UrlDecode(PasskeyRegistry.newUserHandle())

                // ===== 官方 androidx.credentials.webauthn 组包（替代全部自拼协议）=====
                // credentialPublicKey 参数要裸 COSE key 字节（官方 attestation 内嵌它并算 authData，
                // AAGUID 16 字节等布局细节全由官方处理）。
                val xyBytes = base64DecodeStd(publicB64)
                val coseKeyBytes = cborEncode(
                    coseEs256PublicKey(
                        xyBytes.copyOfRange(0, 32),
                        xyBytes.copyOfRange(32, 64)
                    )
                )
                val creationOptions = PublicKeyCredentialCreationOptions(request.requestJson)
                val attestationResponse = AuthenticatorAttestationResponse(
                    requestOptions = creationOptions,
                    credentialId = credentialIdBytes,
                    credentialPublicKey = coseKeyBytes,
                    origin = "https://" + request.rpId,
                    up = true,
                    uv = true,
                    be = false,
                    bs = false,
                    packageName = null,
                    // 浏览器流程传系统下发的 clientDataHash：官方 json() 据此【省略】clientDataJSON
                    // 字段（浏览器会用自己那份替换——省略即最正确的交付），签名基准也对齐浏览器。
                    clientDataHash = clientDataHash
                )
                val credential = FidoPublicKeyCredential(
                    rawId = credentialIdBytes,
                    response = attestationResponse,
                    authenticatorAttachment = "platform"
                )
                // Chrome CredMan 直连通道（value_conversions.cc MakeCredentialResponseFromValue）
                // 在 attestationObject 之外必查 publicKeyAlgorithm/authenticatorData/publicKey
                // 三个字段（官方 json() 只出 attestationObject+transports，缺字段被 Chrome 拒——
                // b7 实锤；KeePassDX vendor 官方类时正是补了这三个字段）。数据全部同源自官方对象。
                val responseJson = org.json.JSONObject(credential.json()).apply {
                    val response = getJSONObject("response")
                    val authDataBytes = attestationAuthData(attestationResponse.attestationObject)
                    response.put("publicKeyAlgorithm", COSE_ALG_ES256)
                    response.put(
                        "authenticatorData",
                        base64Url(authDataBytes) // 与 attestationObject 内逐字节同源，Chrome 比对必过
                    )
                    // SPKI DER（存储格式标准 Base64）解码后转 base64url；Chrome 会解 attestation
                    // 内 COSE key 转 DER 逐字节比对，同一把公钥必一致（b5 编码坑已在此绕开）
                    response.put(
                        "publicKey",
                        base64Url(java.util.Base64.getDecoder().decode(publicDerB64))
                    )
                }.toString()

                // 私钥写入账号段（vault 原子回写）；归属账号自动选择，返回 accountId
                val accountId = PasskeyVaultAccess.savePasskey(
                    context = this,
                    dek = dek,
                    rpId = request.rpId,
                    userName = request.userName.orEmpty(),
                    userHandle = base64Url(userHandle),
                    credentialId = credentialId,
                    privateKeyPkcs8 = privateB64,
                    publicKeyXY = publicB64,
                    publicKeyDer = publicDerB64
                ) ?: error("库内无可归属账号（请先在应用内建一个账号）")

                // registry 索引（出卡查询数据源；与 vault 双写，删除时双删）
                registry.add(
                    PasskeyRegistryEntry(
                        credentialId = credentialId,
                        rpId = request.rpId,
                        userHandle = base64Url(userHandle),
                        userName = request.userName.orEmpty(),
                        accountId = accountId,
                        signCount = 0,
                        createdAt = System.currentTimeMillis()
                    )
                )

                val result = Intent()
                PendingIntentHandler.setCreateCredentialResponse(
                    result,
                    CreatePublicKeyCredentialResponse(responseJson)
                )
                setResult(Activity.RESULT_OK, result)
                finish()
            } finally {
                dek.fill(0)
            }
        }.onFailure {
            Toast.makeText(this, "创建失败：${it.message.orEmpty()}", Toast.LENGTH_SHORT).show()
            finishCanceled()
        }
    }

    /** 标准 Base64 解码（generateSoftwareEs256KeyPair 的公钥坐标编码用标准 Base64）。 */
    private fun base64DecodeStd(text: String): ByteArray =
        java.util.Base64.getDecoder().decode(text)

    private fun finishCanceled() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    /** 透明窗口设置（照 AutofillAuthActivity）：底部浮层、无动画。 */
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
     * 截图保护跟随主界面「禁止截图」设置（照 PasskeyGetActivity 同名函数；
     * 注册浮层虽不含密码输入，弹层提示文案含用户名，一并保护）。
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
