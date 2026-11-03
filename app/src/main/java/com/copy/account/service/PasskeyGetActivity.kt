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

class PasskeyGetActivity : FragmentActivity() {

    private val registry by lazy { PasskeyRegistry(this) }
    private val vaultStore by lazy { SecureVaultStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotFlag()
        setupTransparentWindow()
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
        if (!PasskeyIntegrity.verify(option.requestJson, intent.getByteArrayExtra(PasskeyProviderService.EXTRA_INTEGRITY))) {
            finishCanceled()
            return
        }
        beginAssertion(request, clientDataHash)
    }

    private fun beginAssertion(request: WebAuthnRequest, clientDataHash: ByteArray) {
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
                    val biometric =
                        result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC
                    completeAssertion(request, clientDataHash, result.cryptoObject?.cipher, biometric)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    finishCanceled()
                }
            }
        )
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
                Thread {
                    val result = runCatching {
                        val unlock = vaultStore.unlockWithPassword(password)
                            ?: error("主密码不匹配")
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
        val allowIds = request.allowCredentialIds.orEmpty()
        val material = matches.firstOrNull { allowIds.contains(it.credentialId) }
            ?: matches.firstOrNull { request.userName != null && it.userName == request.userName }
            ?: matches.first()
        val nextCount = signCountInVault(dek, material.credentialId) + 1

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

        persistSignCount(dek, material.credentialId, nextCount)
        if (registry.find(material.credentialId) != null) {
            registry.updateSignCount(material.credentialId, nextCount)
        }

        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(
            result,
            GetCredentialResponse(PublicKeyCredential(responseJson))
        )
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun signCountInVault(dek: ByteArray, credentialId: String): Long {
        val state = vaultStore.load(dek) ?: return 0L
        return state.accounts.asSequence()
            .flatMap { it.passkeys.asSequence() }
            .firstOrNull { it.credentialId == credentialId }?.signCount ?: 0L
    }

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

    private fun setupTransparentWindow() {
        window.requestFeature(Window.FEATURE_NO_TITLE)
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window.setBackgroundDrawableResource(android.R.color.transparent)
    }

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
