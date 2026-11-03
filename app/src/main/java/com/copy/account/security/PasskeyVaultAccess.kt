package com.copy.account.security

import android.content.Context
import com.copy.account.data.model.Account
import com.copy.account.data.model.AccountPasskey

internal object PasskeyVaultAccess {

    data class PasskeyMaterial(
        val credentialId: String,
        val rpId: String,
        val userName: String,
        val userHandle: String,
        val privateKeyPkcs8: String
    )

    fun loadPasskey(context: Context, dek: ByteArray, credentialId: String): PasskeyMaterial? {
        val store = SecureVaultStore(context)
        val state = store.load(dek) ?: return null
        val account = state.accounts.firstOrNull { acc ->
            acc.passkeys.any { it.credentialId == credentialId }
        } ?: return null
        val passkey = account.passkeys.first { it.credentialId == credentialId }
        return PasskeyMaterial(
            credentialId = passkey.credentialId,
            rpId = passkey.rpId,
            userName = passkey.userName,
            userHandle = passkey.userHandle,
            privateKeyPkcs8 = passkey.privateKeyPkcs8
        )
    }

    fun loadPasskeysByRpId(context: Context, dek: ByteArray, rpId: String): List<PasskeyMaterial> {
        val store = SecureVaultStore(context)
        val state = store.load(dek) ?: return emptyList()
        return state.accounts.asSequence()
            .flatMap { it.passkeys.asSequence() }
            .filter { it.rpId == rpId }
            .map {
                PasskeyMaterial(
                    credentialId = it.credentialId,
                    rpId = it.rpId,
                    userName = it.userName,
                    userHandle = it.userHandle,
                    privateKeyPkcs8 = it.privateKeyPkcs8
                )
            }
            .toList()
    }

    fun savePasskey(
        context: Context,
        dek: ByteArray,
        rpId: String,
        userName: String,
        userHandle: String,
        credentialId: String,
        privateKeyPkcs8: String,
        publicKeyXY: String,
        publicKeyDer: String,
        preferAccountId: String? = null
    ): String? {
        val store = SecureVaultStore(context)
        val state = store.load(dek) ?: return null
        val target = selectAccount(state.accounts, rpId, userName, preferAccountId) ?: return null
        val passkey = AccountPasskey(
            credentialId = credentialId,
            rpId = rpId,
            userName = userName,
            userHandle = userHandle,
            privateKeyPkcs8 = privateKeyPkcs8,
            publicKeyXY = publicKeyXY,
            publicKeyDer = publicKeyDer,
            signCount = 0,
            createdAt = System.currentTimeMillis()
        )
        val merged = if (target.passkeys.any { it.credentialId == credentialId }) {
            target.passkeys.map { if (it.credentialId == credentialId) passkey else it }
        } else {
            target.passkeys + passkey
        }
        val nextState = state.copy(
            accounts = state.accounts.map { if (it.id == target.id) target.copy(passkeys = merged) else it }
        )
        store.save(nextState, dek)
        return target.id
    }

    fun deletePasskey(context: Context, dek: ByteArray, credentialId: String): Boolean {
        val store = SecureVaultStore(context)
        val state = store.load(dek) ?: return false
        var changed = false
        val accounts = state.accounts.map { acc ->
            if (acc.passkeys.none { it.credentialId == credentialId }) {
                acc
            } else {
                changed = true
                acc.copy(passkeys = acc.passkeys.filterNot { it.credentialId == credentialId })
            }
        }
        if (!changed) return false
        store.save(state.copy(accounts = accounts), dek)
        return true
    }

    private fun selectAccount(
        accounts: List<Account>,
        rpId: String,
        userName: String,
        preferAccountId: String?
    ): Account? {
        if (accounts.isEmpty()) return null
        preferAccountId?.let { prefer ->
            accounts.firstOrNull { it.id == prefer }?.let { return it }
        }
        val lowerRp = rpId.lowercase()
        accounts.firstOrNull { acc ->
            acc.url.isNotBlank() && domainMatches(acc.url, lowerRp)
        }?.let { return it }
        val mainDomain = lowerRp.substringBefore('.')
        accounts.firstOrNull { acc ->
            (acc.name.contains(lowerRp, ignoreCase = true) ||
                acc.username.contains(lowerRp, ignoreCase = true) ||
                (userName.isNotBlank() && userName.equals(acc.username, ignoreCase = true)))
        }?.let { return it }
        return accounts.first()
    }

    private fun domainMatches(url: String, lowerRp: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: url.lowercase()
        return host == lowerRp || host.endsWith(".$lowerRp")
    }
}
