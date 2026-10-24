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

    // ==================== E. KDF 迭代数透明升级（双向/幂等/数据不重加密）====================

    /** 建库后读 prefs 里的当前迭代数（测试断言用；store 私有键经解锁返回值核对）。 */
    private fun currentIterations() = store.unlockWithPassword("测试密码123")!!.backupKey.iterations

    @Test
    fun iterationUpgrade_lowToHigh_samePasswordStillUnlocks_andDataIntact() {
        store.createInitial("测试密码123", initialState)
        val dekBefore = store.unlockWithPassword("测试密码123")!!.dataKey.copyOf()
        // 300k 库 + 目标 600k：解锁验证通过后应透明升级（返回的 backupKey 已是新参数）。
        val upgraded = store.unlockWithPassword("测试密码123", targetIterations = 600_000)
        assertNotNull(upgraded)
        upgraded!!
        assertEquals(600_000, upgraded.backupKey.iterations)
        // DEK 不变（KEK/DEK 模型：只换包装不重加密），数据照常解出。
        assertArrayEquals(dekBefore, upgraded.dataKey)
        assertEquals(initialState.accounts, upgraded.state.accounts)
        // 升级后常规解锁（不带目标）也成功，且读到的已是新迭代数。
        val after = store.unlockWithPassword("测试密码123")
        assertNotNull(after)
        assertEquals(600_000, after!!.backupKey.iterations)
    }

    @Test
    fun iterationUpgrade_highToLow_downgradeSamePath() {
        store.createInitial("测试密码123", initialState)
        val upgraded = store.unlockWithPassword("测试密码123", targetIterations = 600_000)!!
        assertEquals(600_000, upgraded.backupKey.iterations)
        // 反向：600k 库 + 目标 300k，同一条路降回来。
        val downgraded = store.unlockWithPassword("测试密码123", targetIterations = 300_000)
        assertNotNull(downgraded)
        assertEquals(300_000, downgraded!!.backupKey.iterations)
    }

    @Test
    fun iterationUpgrade_sameValue_isIdempotent() {
        store.createInitial("测试密码123", initialState)
        val first = store.unlockWithPassword("测试密码123", targetIterations = 600_000)!!
        // 目标 == 库存：不触发重包装，backupKey 携带的迭代数即库存值。
        val second = store.unlockWithPassword("测试密码123", targetIterations = 600_000)!!
        assertEquals(first.backupKey.iterations, second.backupKey.iterations)
        assertArrayEquals(first.dataKey, second.dataKey)
    }

    @Test
    fun iterationUpgrade_wrongPassword_stillRejected() {
        store.createInitial("测试密码123", initialState)
        // 升级路径绝不绕过密码验证：错密码 + 目标迭代数 = null。
        assertNull(store.unlockWithPassword("错密码", targetIterations = 600_000))
    }

    @Test
    fun changeMasterPassword_withTargetIterations_appliesImmediately() {
        store.createInitial("测试密码123", initialState)
        val dek = store.unlockWithPassword("测试密码123")!!.dataKey
        // 已解锁改密 + 指定目标迭代数：一次重包装同时完成换密与升参。
        val result = store.changeMasterPassword("新密码456", dek, targetIterations = 500_000)
        assertTrue(result.isSuccess)
        val unlock = store.unlockWithPassword("新密码456")
        assertNotNull(unlock)
        assertEquals(500_000, unlock!!.backupKey.iterations)
        // 旧密码已失效。
        assertNull(store.unlockWithPassword("测试密码123"))
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

    // ==================== E. v2 分段加密 ====================

    @Test
    fun save_writesV2SegmentedEnvelope() {
        val first = store.createInitial("测试密码123", initialState)
        store.save(initialState, first.dataKey)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = java.io.File(context.filesDir, "vault.bin").readText()
        // v2 特征字段：headerIv + accounts 分段列表；version=2。
        assertTrue("应含 headerIv", raw.contains("headerIv"))
        assertTrue("应为 version 2", raw.contains("\"version\":2"))
        // 每个账号一个独立密文段（id 明文保留在索引层）。
        val accountIds = initialState.accounts.map { it.id }
        for (id in accountIds) {
            assertTrue("应含账号段 id=$id", raw.contains("\"id\":\"$id\""))
        }
    }

    @Test
    fun load_v2RoundTrip_preservesFullState() {
        val first = store.createInitial("测试密码123", initialState)
        val modified = initialState.copy(
            accounts = initialState.accounts + Account(id = "seg-acc", name = "分段测试", username = "u2", password = "p2"),
            selectedGroupId = "bank"
        )
        store.save(modified, first.dataKey)
        val loaded = store.load(first.dataKey)
        assertNotNull(loaded)
        assertEquals(modified, loaded)
    }

    @Test
    fun loadAccount_returnsOnlySelectedAccount() {
        val first = store.createInitial("测试密码123", initialState)
        store.save(initialState, first.dataKey)
        // 逐条校验：每个 id 单独解密都能取回一致的账号（互相独立、无串段）。
        for (acc in initialState.accounts) {
            assertEquals(acc, store.loadAccount(first.dataKey, acc.id))
        }
        // 批量 load 与逐段解密结果一致。
        assertEquals(initialState.accounts, store.load(first.dataKey)!!.accounts)
    }

    @Test
    fun loadAccount_wrongDek_returnsNull() {
        val first = store.createInitial("测试密码123", initialState)
        store.save(initialState, first.dataKey)
        val wrongDek = ByteArray(32) { (it + 3).toByte() }
        assertNull(store.loadAccount(wrongDek, initialState.accounts.first().id))
    }

    @Test
    fun loadAccount_missingId_returnsNull() {
        val first = store.createInitial("测试密码123", initialState)
        store.save(initialState, first.dataKey)
        assertNull(store.loadAccount(first.dataKey, "不存在的id"))
    }

    @Test
    fun load_v1Envelope_migratesToV2InPlace() {
        // 手工构造 v1 信封写入 vault.bin，模拟旧版 app 的库。
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vaultFile = java.io.File(context.filesDir, "vault.bin")
        val first = store.createInitial("测试密码123", initialState)
        // createInitial 已经写了 v2；覆盖为 v1 格式（整库一段）。
        val plainV1 = com.copy.account.data.model.vaultJson.encodeToString(PersistedVault.serializer(), initialState)
        val enc = encryptBytesForTest(first.dataKey, plainV1.toByteArray(Charsets.UTF_8))
        val v1Json = com.copy.account.data.model.vaultJson.encodeToString(
            V1EnvelopeForTest.serializer(),
            V1EnvelopeForTest(
                iv = android.util.Base64.encodeToString(enc.second, android.util.Base64.NO_WRAP),
                ciphertext = android.util.Base64.encodeToString(enc.first, android.util.Base64.NO_WRAP)
            )
        )
        vaultFile.writeText(v1Json)

        // 首次 load：识别 v1 → 解密 → 自动以 v2 重写 → 返回状态。
        val loaded = store.load(first.dataKey)
        assertNotNull(loaded)
        assertEquals(initialState, loaded)
        // 库文件已被就地迁移为 v2。
        val raw = vaultFile.readText()
        assertTrue("迁移后应为 v2", raw.contains("headerIv"))
        // 迁移后单段解密立即可用。
        for (acc in initialState.accounts) {
            assertEquals(acc, store.loadAccount(first.dataKey, acc.id))
        }
    }

    /** 测试专用 v1 信封模型（与 VaultStore 私有 EncryptedFile 同构）。 */
    @kotlinx.serialization.Serializable
    private data class V1EnvelopeForTest(val version: Int = 1, val iv: String, val ciphertext: String)

    /** 测试专用 AES-GCM 加密（与 Crypto.encryptBytes 同参；私有不可见故就地复刻）。 */
    private fun encryptBytesForTest(key: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val secretKey = javax.crypto.spec.SecretKeySpec(key, "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey)
        return Pair(cipher.doFinal(plain), cipher.iv)
    }
}
