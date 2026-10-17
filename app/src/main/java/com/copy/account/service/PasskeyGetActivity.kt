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
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
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
import com.copy.account.ui.components.PrimaryButton
import com.copy.account.ui.components.SheetTitleRow
import com.copy.account.ui.components.TextActionButton
import javax.crypto.Cipher

/** 登录确认页。任何失败路径都 setResult(RESULT_CANCELED)，系统放弃本次断言。 */
class PasskeyGetActivity : FragmentActivity() {

    private val registry by lazy { PasskeyRegistry(this) }
    private val vaultStore by lazy { SecureVaultStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        showConfirmCard(request, clientDataHash)
    }

    /** Compose 底部弹层（主界面 AppBottomSheet 同款）：标题（网站域）+「用指纹验证」。 */
    private fun showConfirmCard(request: WebAuthnRequest, clientDataHash: ByteArray) {
        setContent {
            OverlayThemeHost {
                AppBottomSheet(onDismiss = { finishCanceled() }) {
                    SheetTitleRow("通行密钥")
                    Spacer(Modifier.height(8.dp))
                    Text("使用通行密钥登录 ${request.rpId}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "验证指纹后向网站证明身份；私钥随加密库在本机保存",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TextActionButton("取消", onClick = { finishCanceled() }, modifier = Modifier.weight(1f))
                        PrimaryButton("用指纹验证", onClick = { beginAssertion(request, clientDataHash) }, modifier = Modifier.weight(2f))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    /** 弹 BiometricPrompt（CryptoObject=cipher）；通过后解封 DEK 完成断言。 */
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
                    completeAssertion(request, clientDataHash, result.cryptoObject?.cipher ?: cipher)
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

    /** 认证成功后：解 DEK → vault 读私钥 → 官方类组包签名 → signCount 回写 → 回包。 */
    private fun completeAssertion(
        request: WebAuthnRequest,
        clientDataHash: ByteArray,
        cipher: Cipher
    ) {
        runCatching {
            val dek = cipher.doFinal(vaultStore.biometricCiphertext() ?: error("无生物识别包装数据"))
            try {
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
            } finally {
                dek.fill(0)
            }
        }.onFailure {
            Toast.makeText(this, "登录失败：${it.message.orEmpty()}", Toast.LENGTH_SHORT).show()
            finishCanceled()
        }
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
}
