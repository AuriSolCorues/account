package com.copy.account.service

import com.copy.account.runtime.AutofillAccount
import com.copy.account.runtime.toAutofillAccount
import com.copy.account.security.SecureVaultStore
import javax.crypto.Cipher

internal object AutofillSelfHeal {

    const val EXTRA_SELF_HEAL = "com.copy.account.autofill.SELF_HEAL"

    fun unlockAccount(cipher: Cipher, store: SecureVaultStore, accountId: String): AutofillAccount? {
        val dek = runCatching { cipher.doFinal(store.biometricCiphertext() ?: return null) }
            .getOrNull() ?: return null
        try {
            return store.loadAccount(dek, accountId)?.toAutofillAccount()
        } finally {
            dek.fill(0)
        }
    }

    fun unlockAccountWithPassword(store: SecureVaultStore, password: String, accountId: String): AutofillAccount? {
        return runCatching {
            val unlocked = store.unlockWithPassword(password) ?: return null
            val dek = unlocked.dataKey
            unlocked.backupKey.clear()
            try {
                return store.loadAccount(dek, accountId)?.toAutofillAccount()
            } finally {
                dek.fill(0)
            }
        }.getOrNull()
    }
}
