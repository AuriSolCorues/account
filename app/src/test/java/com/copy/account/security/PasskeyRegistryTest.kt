/**
 * 职责：PasskeyRegistry 的重建逻辑单测——导入 .acc/账号本子后 registry 从 vault 账号数据
 *       整表重建（方案：registry 不进备份，vault 的 Account.passkeys 是唯一权威源）。
 *       覆盖：多账号多条目归属、signCount 保真、整表替换语义（旧杂条清空）、空库清表。
 * 测试策略：Robolectric（真实 filesDir 文件读写，照 VaultStoreTest 先例）。
 */
package com.copy.account.security

import androidx.test.core.app.ApplicationProvider
import com.copy.account.data.model.Account
import com.copy.account.data.model.AccountPasskey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasskeyRegistryTest {

    private lateinit var registry: PasskeyRegistry

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // 每个测试独立文件，避免用例间串扰
        java.io.File(context.filesDir, "passkey_registry.json").delete()
        registry = PasskeyRegistry(context)
    }

    /** 造一条 vault 内的通行密钥字段（Account.passkeys 元素）。 */
    private fun vaultPasskey(
        credentialId: String,
        rpId: String = "github.com",
        userName: String = "octocat",
        signCount: Long = 0,
        createdAt: Long = 1000L
    ) = AccountPasskey(
        credentialId = credentialId,
        rpId = rpId,
        userName = userName,
        userHandle = "dXNlcg-$credentialId",
        privateKeyPkcs8 = "cHJpdmlhdGU-$credentialId",
        publicKeyXY = "cHViaWN4eQ",
        publicKeyDer = "cHViaWNkZXI",
        signCount = signCount,
        createdAt = createdAt
    )

    /** 造一个仅含 id 与 passkeys 的最小账号（rebuild 只消费这两个字段）。 */
    private fun vaultAccount(id: String, passkeys: List<AccountPasskey>) = Account(
        id = id, name = id, username = "", password = "", passkeys = passkeys
    )

    @Test
    fun rebuild_restores_entries_with_account_ownership_and_signcount() {
        // 两个账号三条密钥：归属/signCount/时间戳逐一保真
        val accounts = listOf(
            vaultAccount("acc-1", listOf(
                vaultPasskey("cred-a", signCount = 5, createdAt = 1111L),
                vaultPasskey("cred-b", rpId = "gitlab.com", signCount = 7, createdAt = 2222L)
            )),
            vaultAccount("acc-2", listOf(
                vaultPasskey("cred-c", userName = "mona", signCount = 9, createdAt = 3333L)
            ))
        )
        registry.rebuildFromVault(accounts)
        val all = registry.load()
        assertEquals(3, all.size)
        assertEquals("acc-1", registry.find("cred-a")!!.accountId)
        assertEquals("acc-1", registry.find("cred-b")!!.accountId)
        assertEquals("acc-2", registry.find("cred-c")!!.accountId)
        assertEquals(5L, registry.find("cred-a")!!.signCount)
        assertEquals(7L, registry.find("cred-b")!!.signCount)
        assertEquals(9L, registry.find("cred-c")!!.signCount)
        assertEquals("gitlab.com", registry.find("cred-b")!!.rpId)
        assertEquals("mona", registry.find("cred-c")!!.userName)
        // 出卡查询路径按 rpId 命中
        assertEquals(2, registry.byRpId("github.com").size)
        assertEquals(1, registry.byAccountId("acc-2").size)
    }

    @Test
    fun rebuild_replaces_stale_entries_entirely() {
        // 整表替换语义：导入以文件为准，导入前登记的旧杂条一并清掉
        registry.add(PasskeyRegistryEntry(
            credentialId = "stale-cred", rpId = "old.example", userHandle = "a",
            userName = "old", accountId = "acc-old", createdAt = 1L
        ))
        registry.rebuildFromVault(listOf(
            vaultAccount("acc-new", listOf(vaultPasskey("fresh-cred")))
        ))
        val all = registry.load()
        assertEquals(1, all.size)
        assertEquals("fresh-cred", all.single().credentialId)
        assertEquals("acc-new", all.single().accountId)
    }

    @Test
    fun rebuild_with_empty_accounts_clears_table() {
        registry.add(PasskeyRegistryEntry(
            credentialId = "some-cred", rpId = "x.example", userHandle = "a",
            userName = "", accountId = "acc-x", createdAt = 1L
        ))
        registry.rebuildFromVault(emptyList())
        assertTrue(registry.load().isEmpty())
    }
}
