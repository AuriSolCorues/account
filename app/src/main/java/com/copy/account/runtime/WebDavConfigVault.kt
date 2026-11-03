package com.copy.account.runtime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.webdavVaultDataStore by preferencesDataStore(name = "webdav_vault")

data class WebDavConfigRecord(val url: String, val user: String, val passwordEnc: String)

object WebDavConfigVault {
    private val KEY_URL = stringPreferencesKey("vault_url")
    private val KEY_USER = stringPreferencesKey("vault_user")
    private val KEY_PASSWORD_ENC = stringPreferencesKey("vault_password_enc")
    private val KEY_PENDING = booleanPreferencesKey("vault_pending")

    private const val KEY_ALIAS = "webdav_vault_envelope"
    private const val GCM_TAG_BITS = 128

    private fun envelopeKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, envelopeKey())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        return android.util.Base64.encodeToString(iv + sealed, android.util.Base64.NO_WRAP)
    }

    private fun unseal(sealedB64: String): ByteArray? = runCatching {
        val all = android.util.Base64.decode(sealedB64, android.util.Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val data = all.copyOfRange(12, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, envelopeKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(data)
    }.getOrNull()

    fun store(context: Context, url: String, user: String, pluginPasswordEnc: String) {
        val sealedPassword = seal(pluginPasswordEnc.toByteArray(Charsets.UTF_8))
        runBlocking {
            context.webdavVaultDataStore.edit {
                it[KEY_URL] = url
                it[KEY_USER] = user
                it[KEY_PASSWORD_ENC] = sealedPassword
                it[KEY_PENDING] = false
            }
        }
    }

    fun loadForExport(context: Context): WebDavConfigRecord? {
        val v = runBlocking { context.webdavVaultDataStore.data.first() }
        val url = v[KEY_URL] ?: return null
        val user = v[KEY_USER] ?: return null
        val sealed = v[KEY_PASSWORD_ENC] ?: return null
        val pluginEnc = unseal(sealed)?.toString(Charsets.UTF_8) ?: return null
        return WebDavConfigRecord(url, user, pluginEnc)
    }

    fun storeFromImport(context: Context, record: WebDavConfigRecord) {
        val sealedPassword = seal(record.passwordEnc.toByteArray(Charsets.UTF_8))
        runBlocking {
            context.webdavVaultDataStore.edit {
                it[KEY_URL] = record.url
                it[KEY_USER] = record.user
                it[KEY_PASSWORD_ENC] = sealedPassword
                it[KEY_PENDING] = true
            }
        }
    }

    fun fetchPending(context: Context): WebDavConfigRecord? {
        val v = runBlocking { context.webdavVaultDataStore.data.first() }
        if (v[KEY_PENDING] != true) return null
        val url = v[KEY_URL] ?: return null
        val user = v[KEY_USER] ?: return null
        val sealed = v[KEY_PASSWORD_ENC] ?: return null
        val pluginEnc = unseal(sealed)?.toString(Charsets.UTF_8) ?: return null
        runBlocking { context.webdavVaultDataStore.edit { it[KEY_PENDING] = false } }
        return WebDavConfigRecord(url, user, pluginEnc)
    }
}
