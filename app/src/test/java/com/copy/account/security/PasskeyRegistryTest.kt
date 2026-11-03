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
        java.io.File(context.filesDir, "passkey_registry.json").delete()
        registry = PasskeyRegistry(context)
    }

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

    private fun vaultAccount(id: String, passkeys: List<AccountPasskey>) = Account(
        id = id, name = id, username = "", password = "", passkeys = passkeys
    )

    @Test
    fun rebuild_restores_entries_with_account_ownership_and_signcount() {
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
        assertEquals(2, registry.byRpId("github.com").size)
        assertEquals(1, registry.byAccountId("acc-2").size)
    }

    @Test
    fun rebuild_replaces_stale_entries_entirely() {
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
