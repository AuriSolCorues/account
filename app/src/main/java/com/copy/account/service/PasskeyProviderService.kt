package com.copy.account.service

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.ClearCredentialException
import com.copy.account.security.PasskeyIntegrity

class PasskeyProviderService : CredentialProviderService() {

    companion object {
        const val EXTRA_INTEGRITY = "com.copy.account.passkey.INTEGRITY"

    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {
        runCatching {
            val registry = com.copy.account.security.PasskeyRegistry(applicationContext)
            val entries = request.beginGetCredentialOptions.mapNotNull { option ->
                val pkOption = option as? BeginGetPublicKeyCredentialOption ?: return@mapNotNull null
                val rpId = com.copy.account.security.parseWebAuthnRequest(pkOption.requestJson)?.rpId
                    ?: return@mapNotNull null
                val matches = registry.byRpId(rpId)
                if (matches.isEmpty()) return@mapNotNull null
                matches.map { entry ->
                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        entry.credentialId.hashCode(),
                        Intent(this, PasskeyGetActivity::class.java).apply {
                            putExtra(EXTRA_INTEGRITY, PasskeyIntegrity.tag(pkOption.requestJson))
                        },
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    PublicKeyCredentialEntry.Builder(
                        this,
                        entry.userName.ifBlank { entry.rpId },
                        pendingIntent,
                        pkOption
                    ).build()
                }
            }.flatten()
            if (entries.isEmpty()) {
                callback.onResult(BeginGetCredentialResponse.Builder().build())
            } else {
                callback.onResult(BeginGetCredentialResponse.Builder().setCredentialEntries(entries).build())
            }
        }.onFailure { callback.onError(GetCredentialUnknownException(it.message.orEmpty())) }
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {
        runCatching {
            val pkRequest = request as? BeginCreatePublicKeyCredentialRequest
            if (pkRequest == null) {
                callback.onResult(BeginCreateCredentialResponse.Builder().build())
                return
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                pkRequest.requestJson.hashCode(),
                Intent(this, PasskeyCreateActivity::class.java).apply {
                    putExtra(EXTRA_INTEGRITY, PasskeyIntegrity.tag(pkRequest.requestJson))
                },
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            callback.onResult(
                BeginCreateCredentialResponse.Builder()
                    .addCreateEntry(CreateEntry.Builder("保存到账号本子", pendingIntent).build())
                    .build()
            )
        }.onFailure { callback.onError(CreateCredentialUnknownException(it.message.orEmpty())) }
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
        callback.onResult(null)
    }
}
