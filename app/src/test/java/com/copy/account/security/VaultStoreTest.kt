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
        store = SecureVaultStore(ApplicationProvider.getApplicationContext())
    }

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
        assertEquals(32, unlock.dataKey.size)
        assertTrue(unlock.dataKey.any { it != 0.toByte() })
    }

    private fun currentIterations() = store.unlockWithPassword("测试密码123")!!.backupKey.iterations

    @Test
    fun iterationUpgrade_lowToHigh_samePasswordStillUnlocks_andDataIntact() {
        store.createInitial("测试密码123", initialState)
        val dekBefore = store.unlockWithPassword("测试密码123")!!.dataKey.copyOf()
        val upgraded = store.unlockWithPassword("测试密码123", targetIterations = 600_000)
        assertNotNull(upgraded)
        upgraded!!
        assertEquals(600_000, upgraded.backupKey.iterations)
        assertArrayEquals(dekBefore, upgraded.dataKey)
        assertEquals(initialState.accounts, upgraded.state.accounts)
        val after = store.unlockWithPassword("测试密码123")
        assertNotNull(after)
        assertEquals(600_000, after!!.backupKey.iterations)
    }

    @Test
    fun iterationUpgrade_highToLow_downgradeSamePath() {
        store.createInitial("测试密码123", initialState)
        val upgraded = store.unlockWithPassword("测试密码123", targetIterations = 600_000)!!
        assertEquals(600_000, upgraded.backupKey.iterations)
        val downgraded = store.unlockWithPassword("测试密码123", targetIterations = 300_000)
        assertNotNull(downgraded)
        assertEquals(300_000, downgraded!!.backupKey.iterations)
    }

    @Test
    fun iterationUpgrade_sameValue_isIdempotent() {
        store.createInitial("测试密码123", initialState)
        val first = store.unlockWithPassword("测试密码123", targetIterations = 600_000)!!
        val second = store.unlockWithPassword("测试密码123", targetIterations = 600_000)!!
        assertEquals(first.backupKey.iterations, second.backupKey.iterations)
        assertArrayEquals(first.dataKey, second.dataKey)
    }

    @Test
    fun iterationUpgrade_wrongPassword_stillRejected() {
        store.createInitial("测试密码123", initialState)
        assertNull(store.unlockWithPassword("错密码", targetIterations = 600_000))
    }

    @Test
    fun changeMasterPassword_withTargetIterations_appliesImmediately() {
        store.createInitial("测试密码123", initialState)
        val dek = store.unlockWithPassword("测试密码123")!!.dataKey
        val result = store.changeMasterPassword("新密码456", dek, targetIterations = 500_000)
        assertTrue(result.isSuccess)
        val unlock = store.unlockWithPassword("新密码456")
        assertNotNull(unlock)
        assertEquals(500_000, unlock!!.backupKey.iterations)
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
        assertNull(store.unlockWithPassword("任意密码123"))
    }

    @Test
    fun changeMasterPassword_oldFails_newUnlocks_dataIntact() {
        val first = store.createInitial("旧密码1234", initialState)
        val result = store.changeMasterPassword("新密码5678", first.dataKey)
        assertTrue(result.isSuccess)

        assertNull(store.unlockWithPassword("旧密码1234"))
        val reUnlock = store.unlockWithPassword("新密码5678")
        assertNotNull(reUnlock)
        assertArrayEquals(first.dataKey, reUnlock!!.dataKey)
        assertEquals(initialState, reUnlock.state)
    }

    @Test
    fun changeMasterPassword_weakPassword_failsWithRuleHint() {
        val first = store.createInitial("旧密码1234", initialState)
        val result = store.changeMasterPassword("abc", first.dataKey)
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
        assertNull(store.loadCachedBackupKey(first.dataKey))
    }

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

    @Test
    fun loadCachedBackupKey_tamperedCache_returnsNull_notThrows() {
        val first = store.createInitial("测试密码123", initialState)
        assertTrue(store.cacheBackupKey(first.backupKey, first.dataKey).isSuccess)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("account_security", Context.MODE_PRIVATE)

        val cipherB64 = prefs.getString("backup_key_cache", null)!!
        val tampered = Base64.decode(cipherB64, Base64.DEFAULT).copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        prefs.edit().putString("backup_key_cache", Base64.encodeToString(tampered, Base64.NO_WRAP)).commit()

        assertNull(store.loadCachedBackupKey(first.dataKey))

        prefs.edit().putString("backup_key_cache", "!!!不是base64!!!").commit()
        assertNull(store.loadCachedBackupKey(first.dataKey))
    }

    @Test
    fun save_writesV2SegmentedEnvelope() {
        val first = store.createInitial("测试密码123", initialState)
        store.save(initialState, first.dataKey)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = java.io.File(context.filesDir, "vault.bin").readText()
        assertTrue("应含 headerIv", raw.contains("headerIv"))
        assertTrue("应为 version 2", raw.contains("\"version\":2"))
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
        for (acc in initialState.accounts) {
            assertEquals(acc, store.loadAccount(first.dataKey, acc.id))
        }
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
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vaultFile = java.io.File(context.filesDir, "vault.bin")
        val first = store.createInitial("测试密码123", initialState)
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

        val loaded = store.load(first.dataKey)
        assertNotNull(loaded)
        assertEquals(initialState, loaded)
        val raw = vaultFile.readText()
        assertTrue("迁移后应为 v2", raw.contains("headerIv"))
        for (acc in initialState.accounts) {
            assertEquals(acc, store.loadAccount(first.dataKey, acc.id))
        }
    }

    @kotlinx.serialization.Serializable
    private data class V1EnvelopeForTest(val version: Int = 1, val iv: String, val ciphertext: String)

    private fun encryptBytesForTest(key: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val secretKey = javax.crypto.spec.SecretKeySpec(key, "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey)
        return Pair(cipher.doFinal(plain), cipher.iv)
    }
}
