/**
 * 职责：SecureVaultStore 密码流程单测（Robolectric 纯 JVM）——建库、解锁、改密与导出密钥缓存。
 *       生物识别块（AndroidKeyStore/BiometricManager）依赖系统密钥库，不在本地单测范围。
 * 说明：compileSdk 37 超出 Robolectric 支持上限，固定按 SDK 34 模拟（minSdk 28 与 targetSdk 37 之间
 *       任选其一均可，34 是 Robolectric 4.14 支持最完整的主流版本）。
 */
package com.copy.account.security

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.copy.account.data.model.Account
import com.copy.account.data.model.PersistedVault
import com.copy.account.data.model.initialAccounts
import com.copy.account.data.model.initialGroups
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultStoreTest {

    private lateinit var store: SecureVaultStore
    private val initialState = PersistedVault(accounts = initialAccounts, groups = initialGroups)

    @Before
    fun setUp() {
        // Robolectric 默认每个测试新 Application，直接 new 即可，无需手动清理。
        store = SecureVaultStore(ApplicationProvider.getApplicationContext())
    }

    // ==================== A. 首次使用生命周期 ====================

    @Test
    fun hasMasterPassword_initiallyFalse() {
        assertFalse(store.hasMasterPassword())
    }

    @Test
    fun createInitial_then_hasMasterPasswordTrue() {
        store.createInitial("测试密码123", initialState)
        assertTrue(store.hasMasterPassword())
    }

    @Test
    fun unlockWithPassword_correctPassword_returnsStateAndKey() {
        store.createInitial("测试密码123", initialState)
        val unlock = store.unlockWithPassword("测试密码123")
        assertNotNull(unlock)
        unlock!!
        assertEquals(initialState.accounts, unlock.state.accounts)
        assertEquals(initialState.groups, unlock.state.groups)
        assertEquals(initialState.selectedGroupId, unlock.state.selectedGroupId)
        // DEK 为随机 32 字节（AES-256），非空且长度固定。
        assertEquals(32, unlock.dataKey.size)
        assertTrue(unlock.dataKey.any { it != 0.toByte() })
    }

    @Test
    fun unlockWithPassword_wrongPassword_returnsNull() {
        store.createInitial("测试密码123", initialState)
        assertNull(store.unlockWithPassword("错误密码456"))
    }

    @Test
    fun save_then_unlock_roundTrip_preservesModifiedState() {
        val first = store.createInitial("测试密码123", initialState)
        val modified = initialState.copy(
            accounts = initialState.accounts + Account(id = "new-acc", name = "新增账号", username = "u", password = "p"),
            selectedGroupId = "social"
        )
        store.save(modified, first.dataKey)

        val second = store.unlockWithPassword("测试密码123")
        assertNotNull(second)
        assertEquals(modified, second!!.state)
    }

    @Test
    fun unlockWithPassword_beforeCreateInitial_returnsNull() {
        // 实现语义：无 PASSWORD_SALT 时直接返回 null（不抛异常）。
        assertNull(store.unlockWithPassword("任意密码123"))
    }

    // ==================== B. 改密 ====================

    @Test
    fun changeMasterPassword_oldFails_newUnlocks_dataIntact() {
        val first = store.createInitial("旧密码1234", initialState)
        val result = store.changeMasterPassword("新密码5678", first.dataKey)
        assertTrue(result.isSuccess)

        assertNull(store.unlockWithPassword("旧密码1234"))
        val reUnlock = store.unlockWithPassword("新密码5678")
        assertNotNull(reUnlock)
        // 改密只重新包装 DEK、不重加密数据：新解出的 DEK 应与原 DEK 完全一致。
        assertArrayEquals(first.dataKey, reUnlock!!.dataKey)
        assertEquals(initialState, reUnlock.state)
    }

    @Test
    fun changeMasterPassword_weakPassword_failsWithRuleHint() {
        val first = store.createInitial("旧密码1234", initialState)
        val result = store.changeMasterPassword("abc", first.dataKey)
        // 实现用 runCatching 包裹 require：弱密码不会抛出，而是返回 failure。
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("应为 IllegalArgumentException，实际：$error", error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("4-20"))
    }

    @Test
    fun changeMasterPassword_invalidatesBackupCache() {
        val first = store.createInitial("测试密码123", initialState)
        store.cacheBackupKey(first.backupKey, first.dataKey)
        assertNotNull(store.loadCachedBackupKey(first.dataKey))

        assertTrue(store.changeMasterPassword("新密码5678", first.dataKey).isSuccess)
        // 改密的同一原子提交会移除旧缓存（实现中 remove(BACKUP_KEY_CACHE/IV)）。
        assertNull(store.loadCachedBackupKey(first.dataKey))
    }

    // ==================== C. 备份密钥缓存 ====================

    @Test
    fun cacheBackupKey_loadCachedBackupKey_roundTrip() {
        val first = store.createInitial("测试密码123", initialState)
        assertTrue(store.cacheBackupKey(first.backupKey, first.dataKey).isSuccess)

        val loaded = store.loadCachedBackupKey(first.dataKey)
        assertNotNull(loaded)
        assertArrayEquals(first.backupKey.key, loaded!!.key)
        assertArrayEquals(first.backupKey.salt, loaded.salt)
        assertEquals(first.backupKey.iterations, loaded.iterations)
    }

    @Test
    fun loadCachedBackupKey_wrongDek_returnsNull() {
        val first = store.createInitial("测试密码123", initialState)
        assertTrue(store.cacheBackupKey(first.backupKey, first.dataKey).isSuccess)

        val wrongDek = ByteArray(32) { (it + 7).toByte() }
        assertNull(store.loadCachedBackupKey(wrongDek))
    }

    // ==================== D. 脏数据防御 ====================

    @Test
    fun loadCachedBackupKey_tamperedCache_returnsNull_notThrows() {
        val first = store.createInitial("测试密码123", initialState)
        assertTrue(store.cacheBackupKey(first.backupKey, first.dataKey).isSuccess)

        // 私有常量在测试里不可见，直接按字面值访问同一 SharedPreferences。
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("account_security", Context.MODE_PRIVATE)

        // 1) 篡改密文字节：GCM 认证失败路径 → 解密抛异常被吞，返回 null。
        val cipherB64 = prefs.getString("backup_key_cache", null)!!
        val tampered = Base64.decode(cipherB64, Base64.DEFAULT).copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        prefs.edit().putString("backup_key_cache", Base64.encodeToString(tampered, Base64.NO_WRAP)).commit()

        assertNull(store.loadCachedBackupKey(first.dataKey))

        // 2) 塞非法 base64：decode 本身失败也被 runCatching 吞掉，返回 null 不抛异常。
        prefs.edit().putString("backup_key_cache", "!!!不是base64!!!").commit()
        assertNull(store.loadCachedBackupKey(first.dataKey))
    }
}
