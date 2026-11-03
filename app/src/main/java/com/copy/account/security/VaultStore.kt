package com.copy.account.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.biometric.BiometricManager
import com.copy.account.data.model.Account
import com.copy.account.data.model.Group
import com.copy.account.data.model.PersistedVault
import com.copy.account.data.model.vaultJson
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable

private const val SECURITY_PREFS = "account_security"
private const val LEGACY_PASSWORD_HASH = "master_password_hash"
private const val PASSWORD_VERIFIER = "master_password_verifier"
private const val PASSWORD_SALT = "master_password_salt"
private const val PASSWORD_WRAPPED_DEK = "password_wrapped_dek"
private const val PASSWORD_WRAP_IV = "password_wrap_iv"
private const val PASSWORD_ITERATIONS_KEY = "password_iterations"
private const val BIOMETRIC_WRAPPED_DEK = "biometric_wrapped_dek"
private const val BIOMETRIC_WRAP_IV = "biometric_wrap_iv"
private const val BIOMETRIC_ALIAS = "account_vault_biometric"
private const val BACKUP_KEY_CACHE = "backup_key_cache"
private const val BACKUP_KEY_CACHE_IV = "backup_key_cache_iv"
private const val BACKUP_KEY_CACHE_VERSION = 1
private const val VAULT_FILE_NAME = "vault.bin"

@Serializable
private data class EncryptedFile(
    val version: Int = 1,
    val iv: String,
    val ciphertext: String
)

@Serializable
private data class EncryptedAccountSegment(
    val id: String,
    val iv: String,
    val ciphertext: String
)

@Serializable
private data class EncryptedFileV2(
    val version: Int = 2,
    val headerIv: String,
    val headerCiphertext: String,
    val accounts: List<EncryptedAccountSegment>
)

internal data class BackupKeyMaterial(
    val key: ByteArray,
    val salt: ByteArray,
    val iterations: Int
) {
    fun clear() {
        key.fill(0)
        salt.fill(0)
    }
}

internal data class VaultUnlock(
    val dataKey: ByteArray,
    val state: PersistedVault,
    val backupKey: BackupKeyMaterial
)

internal class ExportPasswordRequiredException : IllegalStateException("首次导出需要输入主密码")

internal class SecureVaultStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(SECURITY_PREFS, Context.MODE_PRIVATE)
    private val vaultFile = File(context.filesDir, VAULT_FILE_NAME)

    fun hasMasterPassword(): Boolean = prefs.contains(PASSWORD_VERIFIER) || prefs.contains(LEGACY_PASSWORD_HASH)

    fun changeMasterPassword(newPassword: String, dek: ByteArray): Result<BackupKeyMaterial> =
        changeMasterPassword(newPassword, dek, null)

    fun changeMasterPassword(newPassword: String, dek: ByteArray, targetIterations: Int): Result<BackupKeyMaterial> =
        changeMasterPassword(newPassword, dek, targetIterations as Int?)

    private fun changeMasterPassword(newPassword: String, dek: ByteArray, targetIterations: Int?): Result<BackupKeyMaterial> = runCatching {
        require(isMasterPasswordValid(newPassword)) { MASTER_PASSWORD_RULE_HINT }
        require(dek.size == 32) { "当前密码库密钥无效，请重新解锁" }
        val iterations = (targetIterations ?: prefs.getInt(PASSWORD_ITERATIONS_KEY, DEFAULT_PASSWORD_ITERATIONS))
            .coerceIn(MIN_PASSWORD_ITERATIONS, MAX_PASSWORD_ITERATIONS)
        val newSalt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val material = passwordKeyMaterial(newPassword, newSalt, iterations)
        val wrapped = encryptBytes(material.wrappingKey, dek)
        try {
            val committed = prefs.edit()
                .putString(PASSWORD_SALT, Base64.encodeToString(newSalt, Base64.NO_WRAP))
                .putString(PASSWORD_VERIFIER, Base64.encodeToString(material.verifier, Base64.NO_WRAP))
                .remove(LEGACY_PASSWORD_HASH)
                .putString(PASSWORD_WRAPPED_DEK, Base64.encodeToString(wrapped.ciphertext, Base64.NO_WRAP))
                .putString(PASSWORD_WRAP_IV, Base64.encodeToString(wrapped.iv, Base64.NO_WRAP))
                .putInt(PASSWORD_ITERATIONS_KEY, iterations)
                .remove(BACKUP_KEY_CACHE)
                .remove(BACKUP_KEY_CACHE_IV)
                .commit()
            require(committed) { "主密码保存失败，请重试" }
            BackupKeyMaterial(material.wrappingKey, newSalt, iterations)
        } catch (error: Throwable) {
            newSalt.fill(0)
            material.wrappingKey.fill(0)
            throw error
        } finally {
            material.verifier.fill(0)
            wrapped.iv.fill(0)
            wrapped.ciphertext.fill(0)
        }
    }

    fun createInitial(password: String, state: PersistedVault): VaultUnlock {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val material = passwordKeyMaterial(password, salt, DEFAULT_PASSWORD_ITERATIONS)
        val wrapped = encryptBytes(material.wrappingKey, dek)
        try {
            save(state, dek)
            require(prefs.edit()
                .putString(PASSWORD_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(PASSWORD_VERIFIER, Base64.encodeToString(material.verifier, Base64.NO_WRAP))
                .putString(PASSWORD_WRAPPED_DEK, Base64.encodeToString(wrapped.ciphertext, Base64.NO_WRAP))
                .putString(PASSWORD_WRAP_IV, Base64.encodeToString(wrapped.iv, Base64.NO_WRAP))
                .putInt(PASSWORD_ITERATIONS_KEY, DEFAULT_PASSWORD_ITERATIONS)
                .commit()) { "主密码保存失败，请重试" }
            return VaultUnlock(dek, state, BackupKeyMaterial(material.wrappingKey, salt, DEFAULT_PASSWORD_ITERATIONS))
        } catch (error: Throwable) {
            dek.fill(0)
            salt.fill(0)
            material.wrappingKey.fill(0)
            throw error
        } finally {
            material.verifier.fill(0)
            wrapped.iv.fill(0)
            wrapped.ciphertext.fill(0)
        }
    }

    fun unlockWithPassword(password: String, targetIterations: Int? = null): VaultUnlock? {
        val salt = prefs.getString(PASSWORD_SALT, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
        val iterations = prefs.getInt(PASSWORD_ITERATIONS_KEY, DEFAULT_PASSWORD_ITERATIONS)
        val verifier = prefs.getString(PASSWORD_VERIFIER, null)?.let { Base64.decode(it, Base64.DEFAULT) }
        val legacyKek = prefs.getString(LEGACY_PASSWORD_HASH, null)?.let { Base64.decode(it, Base64.DEFAULT) }
        var wrappingKey: ByteArray? = null
        return try {
            wrappingKey = if (verifier != null) {
                val material = passwordKeyMaterial(password, salt, iterations)
                if (!MessageDigest.isEqual(verifier, material.verifier)) {
                    material.clear()
                    return null
                }
                material.wrappingKey.also { material.verifier.fill(0) }
            } else {
                val legacy = legacyKek ?: return null
                val key = passwordHash(password, salt, iterations)
                if (!MessageDigest.isEqual(legacy, key)) {
                    key.fill(0)
                    return null
                }
                key
            }
            val wrapped = prefs.getString(PASSWORD_WRAPPED_DEK, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
            val iv = prefs.getString(PASSWORD_WRAP_IV, null)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
            val key = wrappingKey ?: return null
            val dek = runCatching { decryptBytes(key, iv, wrapped) }.getOrNull() ?: return null
            val state = load(dek) ?: return null
            val needsUpgrade = targetIterations != null && needsIterationUpgrade(iterations, targetIterations)
            val backupKey = when {
                verifier == null -> migrateLegacyPassword(password, dek, iterations)
                needsUpgrade -> upgradeIterations(password, dek, targetIterations!!)
                else -> BackupKeyMaterial(key.copyOf(), salt.copyOf(), iterations)
            }
            VaultUnlock(dek, state, backupKey)
        } finally {
            salt.fill(0)
            verifier?.fill(0)
            legacyKek?.fill(0)
            wrappingKey?.fill(0)
        }
    }

    private fun upgradeIterations(password: String, dek: ByteArray, targetIterations: Int): BackupKeyMaterial =
        changeMasterPassword(password, dek, targetIterations).getOrThrow()

    private fun migrateLegacyPassword(password: String, dek: ByteArray, iterations: Int): BackupKeyMaterial =
        changeMasterPassword(password, dek, iterations).getOrThrow()

    fun cacheBackupKey(material: BackupKeyMaterial, dek: ByteArray): Result<Unit> = runCatching {
        require(dek.size == 32 && material.key.size == 32 && material.salt.size >= 16) { "备份密钥无效" }
        val plain = ByteBuffer.allocate(12 + material.salt.size + material.key.size)
            .putInt(BACKUP_KEY_CACHE_VERSION)
            .putInt(material.iterations)
            .putInt(material.salt.size)
            .put(material.salt)
            .put(material.key)
            .array()
        try {
            val encrypted = encryptBytes(dek, plain)
            try {
                require(prefs.edit()
                    .putString(BACKUP_KEY_CACHE, Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP))
                    .putString(BACKUP_KEY_CACHE_IV, Base64.encodeToString(encrypted.iv, Base64.NO_WRAP))
                    .commit()) { "导出密钥缓存保存失败" }
            } finally {
                encrypted.iv.fill(0)
                encrypted.ciphertext.fill(0)
            }
        } finally {
            plain.fill(0)
        }
    }

    fun loadCachedBackupKey(dek: ByteArray): BackupKeyMaterial? {
        if (dek.size != 32) return null
        val ciphertext = prefs.getString(BACKUP_KEY_CACHE, null)?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() } ?: return null
        val iv = prefs.getString(BACKUP_KEY_CACHE_IV, null)?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() } ?: run {
            ciphertext.fill(0)
            return null
        }
        val plain = try {
            decryptBytes(dek, iv, ciphertext)
        } catch (_: Throwable) {
            null
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        } ?: return null
        return try {
            val buffer = ByteBuffer.wrap(plain)
            require(buffer.int == BACKUP_KEY_CACHE_VERSION) { "未知的导出密钥缓存版本" }
            val iterations = buffer.int
            val saltSize = buffer.int
            require(iterations in 10_000..2_000_000 && saltSize >= 16 && buffer.remaining() == saltSize + 32) { "导出密钥缓存无效" }
            val salt = ByteArray(saltSize).also(buffer::get)
            val key = ByteArray(32).also(buffer::get)
            BackupKeyMaterial(key, salt, iterations)
        } catch (_: Throwable) {
            null
        } finally {
            plain.fill(0)
        }
    }

    fun save(state: PersistedVault, dek: ByteArray) {
        val headerPlain = vaultJson.encodeToString(
            VaultHeader.serializer(),
            VaultHeader(groups = state.groups, selectedGroupId = state.selectedGroupId)
        ).toByteArray(Charsets.UTF_8)
        val headerEnc = encryptBytes(dek, headerPlain)
        headerPlain.fill(0)
        val segments = state.accounts.map { account ->
            val plain = vaultJson.encodeToString(Account.serializer(), account).toByteArray(Charsets.UTF_8)
            try {
                val enc = encryptBytes(dek, plain)
                EncryptedAccountSegment(
                    id = account.id,
                    iv = Base64.encodeToString(enc.iv, Base64.NO_WRAP),
                    ciphertext = Base64.encodeToString(enc.ciphertext, Base64.NO_WRAP)
                )
            } finally {
                plain.fill(0)
            }
        }
        val encoded = vaultJson.encodeToString(
            EncryptedFileV2.serializer(),
            EncryptedFileV2(
                headerIv = Base64.encodeToString(headerEnc.iv, Base64.NO_WRAP),
                headerCiphertext = Base64.encodeToString(headerEnc.ciphertext, Base64.NO_WRAP),
                accounts = segments
            )
        ).toByteArray(Charsets.UTF_8)
        headerEnc.iv.fill(0)
        headerEnc.ciphertext.fill(0)
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

    @Serializable
    private data class VaultHeader(
        val groups: List<Group>,
        val selectedGroupId: String
    )

    fun load(dek: ByteArray): PersistedVault? = runCatching {
        if (!vaultFile.exists()) return null
        val bytes = FileInputStream(vaultFile).use { it.readBytes() }
        val text = bytes.toString(Charsets.UTF_8)
        if (text.contains("headerIv")) {
            val envelope = vaultJson.decodeFromString(EncryptedFileV2.serializer(), text)
            val headerPlain = decryptBytes(
                dek,
                Base64.decode(envelope.headerIv, Base64.DEFAULT),
                Base64.decode(envelope.headerCiphertext, Base64.DEFAULT)
            )
            val header = try {
                vaultJson.decodeFromString(VaultHeader.serializer(), headerPlain.toString(Charsets.UTF_8))
            } finally {
                headerPlain.fill(0)
            }
            val accounts = envelope.accounts.map { segment ->
                val plain = decryptBytes(
                    dek,
                    Base64.decode(segment.iv, Base64.DEFAULT),
                    Base64.decode(segment.ciphertext, Base64.DEFAULT)
                )
                try {
                    vaultJson.decodeFromString(Account.serializer(), plain.toString(Charsets.UTF_8))
                } finally {
                    plain.fill(0)
                }
            }
            PersistedVault(accounts = accounts, groups = header.groups, selectedGroupId = header.selectedGroupId)
        } else {
            val envelope = vaultJson.decodeFromString(EncryptedFile.serializer(), text)
            val plain = decryptBytes(dek, Base64.decode(envelope.iv, Base64.DEFAULT), Base64.decode(envelope.ciphertext, Base64.DEFAULT))
            val state: PersistedVault = try {
                vaultJson.decodeFromString(PersistedVault.serializer(), plain.toString(Charsets.UTF_8))
            } finally {
                plain.fill(0)
            }
            runCatching { save(state, dek) }
            state
        }
    }.getOrNull()

    fun loadAccount(dek: ByteArray, accountId: String): Account? = runCatching {
        if (!vaultFile.exists()) return null
        val bytes = FileInputStream(vaultFile).use { it.readBytes() }
        val envelope = vaultJson.decodeFromString(EncryptedFileV2.serializer(), bytes.toString(Charsets.UTF_8))
        val segment = envelope.accounts.firstOrNull { it.id == accountId } ?: return null
        val plain = decryptBytes(
            dek,
            Base64.decode(segment.iv, Base64.DEFAULT),
            Base64.decode(segment.ciphertext, Base64.DEFAULT)
        )
        try {
            vaultJson.decodeFromString(Account.serializer(), plain.toString(Charsets.UTF_8))
        } finally {
            plain.fill(0)
        }
    }.getOrNull()

    fun biometricAvailable(): Boolean = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun biometricKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(BIOMETRIC_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(BIOMETRIC_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
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
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(BIOMETRIC_ALIAS)
        }
    }
}
