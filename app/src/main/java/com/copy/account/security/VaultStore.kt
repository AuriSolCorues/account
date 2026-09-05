package com.copy.account.security

import android.content.Context
import android.os.Build
import android.security.keystore.*
import android.util.AtomicFile
import android.util.Base64
import androidx.biometric.BiometricManager
import java.io.File
import java.io.FileInputStream
import java.security.*
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.*
import com.copy.account.data.model.PersistedVault
import com.copy.account.data.model.Group
import com.copy.account.data.model.Account

private const val SECURITY_PREFS = "account_security"
private const val PASSWORD_HASH = "master_password_hash"
private const val PASSWORD_SALT = "master_password_salt"
private const val PASSWORD_WRAPPED_DEK = "password_wrapped_dek"
private const val PASSWORD_WRAP_IV = "password_wrap_iv"
private const val PASSWORD_ITERATIONS_KEY = "password_iterations"
private const val BIOMETRIC_WRAPPED_DEK = "biometric_wrapped_dek"
private const val BIOMETRIC_WRAP_IV = "biometric_wrap_iv"
private const val BIOMETRIC_ALIAS = "account_vault_biometric"
private const val VAULT_FILE_NAME = "vault.bin"

@Serializable
private data class EncryptedFile(
    val version: Int = 1,
    val iv: String,
    val ciphertext: String
)

internal class SecureVaultStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(SECURITY_PREFS, Context.MODE_PRIVATE)
    private val vaultFile = File(context.filesDir, VAULT_FILE_NAME)

    fun hasMasterPassword(): Boolean = prefs.contains(PASSWORD_HASH)

    /** 已解锁时使用当前 DEK 重新包装，直接替换主密码的 KEK 元数据。 */
    fun changeMasterPassword(newPassword: String, dek: ByteArray): Result<Unit> = runCatching {
        require(isMasterPasswordValid(newPassword)) { "主密码长度需为 4-20 个字符" }
        require(dek.size == 32) { "当前密码库密钥无效，请重新解锁" }
        val newSalt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val newKek = passwordHash(newPassword, newSalt, prefs.getInt(PASSWORD_ITERATIONS_KEY, DEFAULT_PASSWORD_ITERATIONS))
        val wrapped = encryptBytes(newKek, dek)
        try {
            val committed = prefs.edit()
                .putString(PASSWORD_SALT, Base64.encodeToString(newSalt, Base64.NO_WRAP))
                .putString(PASSWORD_HASH, Base64.encodeToString(newKek, Base64.NO_WRAP))
                .putString(PASSWORD_WRAPPED_DEK, Base64.encodeToString(wrapped.ciphertext, Base64.NO_WRAP))
                .putString(PASSWORD_WRAP_IV, Base64.encodeToString(wrapped.iv, Base64.NO_WRAP))
                .commit()
            require(committed) { "主密码保存失败，请重试" }
        } finally {
            newSalt.fill(0)
            newKek.fill(0)
            wrapped.iv.fill(0)
            wrapped.ciphertext.fill(0)
        }
    }

    /** 导出无需再次输入密码，复用首次设置主密码时派生的 KEK 和盐。 */
    fun masterKeyMaterial(): Triple<ByteArray, ByteArray, Int>? {
        val salt = prefs.getString(PASSWORD_SALT, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
        val key = prefs.getString(PASSWORD_HASH, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
        val iterations = prefs.getInt(PASSWORD_ITERATIONS_KEY, DEFAULT_PASSWORD_ITERATIONS)
        return if (salt.isNotEmpty() && key.size == 32) Triple(key, salt, iterations) else null
    }

    fun createInitial(password: String, state: PersistedVault): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val kek = passwordHash(password, salt, DEFAULT_PASSWORD_ITERATIONS)
        val wrapped = encryptBytes(kek, dek)
        prefs.edit()
            .putString(PASSWORD_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(PASSWORD_HASH, Base64.encodeToString(kek, Base64.NO_WRAP))
            .putString(PASSWORD_WRAPPED_DEK, Base64.encodeToString(wrapped.ciphertext, Base64.NO_WRAP))
            .putString(PASSWORD_WRAP_IV, Base64.encodeToString(wrapped.iv, Base64.NO_WRAP))
            .putInt(PASSWORD_ITERATIONS_KEY, DEFAULT_PASSWORD_ITERATIONS)
            .apply()
        save(state, dek)
        return dek
    }

    fun unlockWithPassword(password: String): Pair<ByteArray, PersistedVault>? {
        val salt = prefs.getString(PASSWORD_SALT, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
        val expected = prefs.getString(PASSWORD_HASH, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
        val iterations = prefs.getInt(PASSWORD_ITERATIONS_KEY, DEFAULT_PASSWORD_ITERATIONS)
        val kek = passwordHash(password, salt, iterations)
        return try {
            if (!MessageDigest.isEqual(expected, kek)) return null
            val wrapped = prefs.getString(PASSWORD_WRAPPED_DEK, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
            val iv = prefs.getString(PASSWORD_WRAP_IV, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
            // 密钥材料缺失或损坏时直接失败，绝不生成新 DEK 或回退到示例库，避免静默丢数据。
            val dek = runCatching { decryptBytes(kek, iv, wrapped) }.getOrNull() ?: return null
            val state = load(dek) ?: return null
            dek to state
        } finally {
            salt.fill(0)
            expected.fill(0)
            kek.fill(0)
        }
    }

    fun save(state: PersistedVault, dek: ByteArray) {
        val plain = vaultJson.encodeToString(PersistedVault.serializer(), state).toByteArray(Charsets.UTF_8)
        val payload = encryptBytes(dek, plain)
        val encoded = vaultJson.encodeToString(
            EncryptedFile.serializer(),
            EncryptedFile(iv = Base64.encodeToString(payload.iv, Base64.NO_WRAP), ciphertext = Base64.encodeToString(payload.ciphertext, Base64.NO_WRAP))
        ).toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(vaultFile)
        val stream = atomic.startWrite()
        try {
            stream.write(encoded)
            stream.flush()
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun load(dek: ByteArray): PersistedVault? = runCatching {
        if (!vaultFile.exists()) return null
        val bytes = FileInputStream(vaultFile).use { it.readBytes() }
        val envelope = vaultJson.decodeFromString(EncryptedFile.serializer(), bytes.toString(Charsets.UTF_8))
        val plain = decryptBytes(dek, Base64.decode(envelope.iv, Base64.DEFAULT), Base64.decode(envelope.ciphertext, Base64.DEFAULT))
        vaultJson.decodeFromString(PersistedVault.serializer(), plain.toString(Charsets.UTF_8))
    }.getOrNull()

    fun biometricAvailable(): Boolean = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun biometricKey(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(BIOMETRIC_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(BIOMETRIC_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    fun beginBiometricEncrypt(): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, biometricKey())
        return cipher
    }

    fun beginBiometricDecrypt(): Cipher? {
        val wrapped = prefs.getString(BIOMETRIC_WRAPPED_DEK, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
        val iv = prefs.getString(BIOMETRIC_WRAP_IV, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, biometricKey(), GCMParameterSpec(128, iv))
        return cipher
    }

    fun saveBiometricWrapped(cipher: Cipher, dek: ByteArray) {
        val encrypted = cipher.doFinal(dek)
        prefs.edit()
            .putString(BIOMETRIC_WRAPPED_DEK, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(BIOMETRIC_WRAP_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun biometricCiphertext(): ByteArray? = prefs.getString(BIOMETRIC_WRAPPED_DEK, null)?.let { Base64.decode(it, Base64.DEFAULT) }

    fun disableBiometric() {
        prefs.edit().remove(BIOMETRIC_WRAPPED_DEK).remove(BIOMETRIC_WRAP_IV).apply()
        runCatching {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(BIOMETRIC_ALIAS)
        }
    }
}
