/**
 * 职责：密码库 vault.bin 的建库、加解锁与原子落盘。核心是 KEK/DEK 双层密钥模型——
 *       主密码经 PBKDF2 派生 KEK（只用来包一层 DEK），随机 32 字节 DEK 才真正加密 vault.bin；
 *       生物识别再用 AndroidKeyStore 里不可导出的密钥包第二份 DEK。改主密码只重新包装、不重加密数据。
 * 架构位置：AccountApp 解锁流程调用（remember { SecureVaultStore(context) } 持有实例）；
 *           加解密原语来自 security/Crypto.kt；密钥元数据存 SharedPreferences(account_security)，
 *           库文件是 filesDir/vault.bin。
 * Python 类比：SharedPreferences ≈ 同步阻塞的 configparser（内存缓存 + 整文件重写落盘）；
 *           AtomicFile ≈ 先写临时文件再 os.replace 的原子写；AndroidKeyStore 无 Python 等价物（见下）。
 */
package com.copy.account.security

import android.content.Context
import android.os.Build
import android.security.keystore.*
import android.util.AtomicFile
import android.util.Base64
import androidx.biometric.BiometricManager
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.*
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.*
import com.copy.account.data.model.PersistedVault

// 以下常量是 SharedPreferences(account_security) 的 key 与库文件名：里面只存盐、验证值、
// 包装后的 DEK 与 PBKDF2 迭代数，绝不存明文主密码、裸 DEK 或可解开 DEK 的 KEK。
private const val SECURITY_PREFS = "account_security"
/** 旧版直接存 KEK 的键；仅用于首次成功解锁后迁移。 */
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

/** 仅在已解锁会话中保留，锁定时由调用方清零。 */
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

/** 仅在旧安装尚未建立本地加密导出缓存时，用于让备份页请求一次主密码。 */
internal class ExportPasswordRequiredException : IllegalStateException("首次导出需要输入主密码")

internal class SecureVaultStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(SECURITY_PREFS, Context.MODE_PRIVATE)
    private val vaultFile = File(context.filesDir, VAULT_FILE_NAME)

    fun hasMasterPassword(): Boolean = prefs.contains(PASSWORD_VERIFIER) || prefs.contains(LEGACY_PASSWORD_HASH)

    /** 已解锁时使用当前 DEK 重新包装，直接替换主密码的 KEK 元数据。 */
    fun changeMasterPassword(newPassword: String, dek: ByteArray): Result<BackupKeyMaterial> = runCatching {
        require(isMasterPasswordValid(newPassword)) { "主密码长度需为 4-20 个字符" }
        require(dek.size == 32) { "当前密码库密钥无效，请重新解锁" }
        val newSalt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val material = passwordKeyMaterial(newPassword, newSalt, prefs.getInt(PASSWORD_ITERATIONS_KEY, DEFAULT_PASSWORD_ITERATIONS))
        val wrapped = encryptBytes(material.wrappingKey, dek)
        try {
            val committed = prefs.edit()
                .putString(PASSWORD_SALT, Base64.encodeToString(newSalt, Base64.NO_WRAP))
                .putString(PASSWORD_VERIFIER, Base64.encodeToString(material.verifier, Base64.NO_WRAP))
                .remove(LEGACY_PASSWORD_HASH)
                .putString(PASSWORD_WRAPPED_DEK, Base64.encodeToString(wrapped.ciphertext, Base64.NO_WRAP))
                .putString(PASSWORD_WRAP_IV, Base64.encodeToString(wrapped.iv, Base64.NO_WRAP))
                // 新缓存需由新的主密码派生密钥生成；本次提交先原子废弃旧缓存。
                .remove(BACKUP_KEY_CACHE)
                .remove(BACKUP_KEY_CACHE_IV)
                .commit()
            require(committed) { "主密码保存失败，请重试" }
            BackupKeyMaterial(material.wrappingKey, newSalt, prefs.getInt(PASSWORD_ITERATIONS_KEY, DEFAULT_PASSWORD_ITERATIONS))
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

    /** 首建：只落盘验证值与包装后的 DEK。 */
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

    fun unlockWithPassword(password: String): VaultUnlock? {
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
            val backupKey = if (verifier == null) migrateLegacyPassword(password, dek)
            else BackupKeyMaterial(key.copyOf(), salt.copyOf(), iterations)
            VaultUnlock(dek, state, backupKey)
        } finally {
            salt.fill(0)
            verifier?.fill(0)
            legacyKek?.fill(0)
            wrappingKey?.fill(0)
        }
    }

    private fun migrateLegacyPassword(password: String, dek: ByteArray): BackupKeyMaterial =
        changeMasterPassword(password, dek).getOrThrow()

    /**
     * 导出密钥缓存始终由当前 DEK 加密；缓存丢失或损坏只影响免输入导出，不影响 vault.bin。
     * SharedPreferences 的单次 commit 会将 IV 与密文一起原子替换。
     */
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

    /** 点击导出时才解密缓存；任何校验或认证失败都按无缓存处理。 */
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
        val plain = vaultJson.encodeToString(PersistedVault.serializer(), state).toByteArray(Charsets.UTF_8)
        val payload = encryptBytes(dek, plain)
        val encoded = vaultJson.encodeToString(
            EncryptedFile.serializer(),
            EncryptedFile(iv = Base64.encodeToString(payload.iv, Base64.NO_WRAP), ciphertext = Base64.encodeToString(payload.ciphertext, Base64.NO_WRAP))
        ).toByteArray(Charsets.UTF_8)
        // AtomicFile 原子写：先写同目录临时文件、成功才改名顶替（≈ tmp 文件 + os.replace），
        // 写到一半崩溃或断电都不会损坏旧的 vault.bin。
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

    // AndroidKeyStore：硬件背书的系统密钥库，密钥不可导出（连 App 自己都取不走字节），Python 无等价物。
    // setUserAuthenticationRequired(true) = 每次使用该密钥必须先通过生物识别，由系统强制把关。
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
            // 仅 API 28/29 走到这里（minSdk 28）：替代用的 setUserAuthenticationParameters 是
            // API 30 才引入的，平台在此版本没有等价 API，废弃调用无从回避，故显式抑制。
            // -1 = 每次使用该密钥都必须认证，不留宽限期。
            // 已知且不可修复的强度差异：旧接口只要求「用户已认证」而不限定认证方式，PIN/图案/
            // 弱生物识别均可解锁，比 API 30+ 分支的 AUTH_BIOMETRIC_STRONG 弱；且上方 getKey
            // 会直接复用已存在的密钥，用户日后升级到 30+ 也不会自动收紧——KeyGenParameterSpec
            // 的认证约束只在生成密钥时生效，事后无法改严。UI 层由 biometricAvailable() 的
            // canAuthenticate(BIOMETRIC_STRONG) 把关，密钥层的差异是平台限制而非疏漏。
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
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(BIOMETRIC_ALIAS)
        }
    }
}
