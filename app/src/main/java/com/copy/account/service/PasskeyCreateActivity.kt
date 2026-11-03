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

class PasskeyCreateActivity : FragmentActivity() {

    private val registry by lazy { PasskeyRegistry(this) }
    private val vaultStore by lazy { SecureVaultStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotFlag()
        setupTransparentWindow()
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
        if (!PasskeyIntegrity.verify(pkRequest.requestJson, intent.getByteArrayExtra(PasskeyProviderService.EXTRA_INTEGRITY))) {
            finishCanceled()
            return
        }
        showConfirmCard(request, clientDataHash)
    }

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
                    clientDataHash = clientDataHash
                )
                val credential = FidoPublicKeyCredential(
                    rawId = credentialIdBytes,
                    response = attestationResponse,
                    authenticatorAttachment = "platform"
                )
                val responseJson = org.json.JSONObject(credential.json()).apply {
                    val response = getJSONObject("response")
                    val authDataBytes = attestationAuthData(attestationResponse.attestationObject)
                    response.put("publicKeyAlgorithm", COSE_ALG_ES256)
                    response.put(
                        "authenticatorData",
                        base64Url(authDataBytes)
                    )
                    response.put(
                        "publicKey",
                        base64Url(java.util.Base64.getDecoder().decode(publicDerB64))
                    )
                }.toString()

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

    private fun base64DecodeStd(text: String): ByteArray =
        java.util.Base64.getDecoder().decode(text)

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
