/**
 * 职责：扩展 WebDAV 配置的本体托管区——扩展保存连接配置时经桥推来密文副本，这里落
 *       本体私有 DataStore（密码字段保持扩展侧 Keystore 密文不动，本体重存一层自己的
 *       Keystore 信封）；导出 .acc 时读出放进备份载荷，恢复 .acc 时写回托管区并置
 *       「待取」标记，扩展下次 loadInitial 经桥取回——换机免重输（用户痛点定案）。
 *       安全等价性：.acc 内层本就存明文账号密码（备份密码 AES-GCM 全包加密），WebDAV
 *       应用密码放同一层不降级；托管区在本体私有沙箱 + 双层密文，无新增攻击面。
 * 架构位置：runtime/ 层（ExtensionBridgeProvider 的 push/fetch 分发到此类）。
 * Python 类比：≈ 一个带信封加密的小型 keyring——set/get + pending 标记，其余不管。
 */
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

/** 托管记录：服务器/账号明文 + 应用密码密文（扩展侧 Keystore 密文，本体不解其内容）。 */
data class WebDavConfigRecord(val url: String, val user: String, val passwordEnc: String)

/**
 * WebDAV 配置托管区。密码密文本体侧再套一层自家 Keystore AES-GCM 信封：
 * DataStore 文件即便被 root 读走，密码也是一串无法解开的密文（扩展 Keystore 在另一沙箱）。
 */
object WebDavConfigVault {
    private val KEY_URL = stringPreferencesKey("vault_url")
    private val KEY_USER = stringPreferencesKey("vault_user")
    private val KEY_PASSWORD_ENC = stringPreferencesKey("vault_password_enc") // 双层密文（本体信封+扩展密文）
    private val KEY_PENDING = booleanPreferencesKey("vault_pending")          // .acc 恢复后待扩展取回

    private const val KEY_ALIAS = "webdav_vault_envelope"
    private const val GCM_TAG_BITS = 128

    /** get-or-create 本体侧 AES-256 信封密钥（AndroidKeyStore，不可提取）。 */
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

    /** 明文 → IV‖密文 的 Base64（信封加密，每次新 IV）。 */
    private fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, envelopeKey())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        return android.util.Base64.encodeToString(iv + sealed, android.util.Base64.NO_WRAP)
    }

    /** IV‖密文 Base64 → 明文；解不开（密钥被清/数据损坏）= null，调用方按无配置处理。 */
    private fun unseal(sealedB64: String): ByteArray? = runCatching {
        val all = android.util.Base64.decode(sealedB64, android.util.Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val data = all.copyOfRange(12, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, envelopeKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(data)
    }.getOrNull()

    /** 扩展 push 而来：url/user 明文直存（非机密），密码双层密文（本体信封套扩展密文）。 */
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

    /** 导出 .acc 读取：无记录 = null（备份里就不带该字段）。密码解信封还原成扩展密文。 */
    fun loadForExport(context: Context): WebDavConfigRecord? {
        val v = runBlocking { context.webdavVaultDataStore.data.first() }
        val url = v[KEY_URL] ?: return null
        val user = v[KEY_USER] ?: return null
        val sealed = v[KEY_PASSWORD_ENC] ?: return null
        val pluginEnc = unseal(sealed)?.toString(Charsets.UTF_8) ?: return null
        return WebDavConfigRecord(url, user, pluginEnc)
    }

    /** 恢复 .acc 写回：整组覆盖 + 置待取标记（扩展下次 loadInitial 经桥 fetch）。 */
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

    /** 扩展 fetch：只在待取标记为 true 时返回（取走即清标记）；false/null = 无待取。 */
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
