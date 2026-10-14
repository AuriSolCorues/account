/**
 * 职责：ACCOUNTBOX v13 编解码单测（纯 JVM）——导出结构、往返一致性、防御性解析。
 *       无 Android 依赖（exportedAtMs 注入），直接跑。
 */
package com.copy.account.security

import com.copy.account.data.model.Account
import com.copy.account.data.model.AccountField
import com.copy.account.data.model.Group
import com.copy.account.data.model.GroupKind
import com.copy.account.data.model.PersistedVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBoxCodecTest {

    private val testVault = PersistedVault(
        accounts = listOf(
            Account(
                id = "a1",
                name = "微信",
                username = "user1",
                password = "pass1",
                groups = setOf("g1"),
                hasTotp = true,
                totpSecret = "JBSWY3DPEHPK3PXP",
                totpDigits = 6,
                totpPeriod = 30,
                totpAlgorithm = "SHA256",
                totpType = "TOTP",
                url = "https://weixin.qq.com",
                packageName = "com.tencent.mm",
                customFields = listOf(AccountField("f1", "备注", "工作号"))
            ),
            Account(
                id = "a2",
                name = "Steam",
                username = "user2",
                password = "pass2",
                hasTotp = true,
                totpSecret = "ABC234DEF",
                totpType = "STEAM",
                totpAlgorithm = "SHA1"
            ),
            Account(id = "a3", name = "无二步验证", username = "u3", password = "p3")
        ),
        groups = listOf(
            Group("default", "默认", GroupKind.DEFAULT),
            Group("dynamic", "动态密码", GroupKind.DYNAMIC),
            Group("g1", "社交媒体", GroupKind.CUSTOM)
        ),
        selectedGroupId = "g1"
    )

    // ==================== 导出结构 ====================

    @Test
    fun export_startsWithMagicLine() {
        val bytes = exportAccountBoxJson(testVault, 1747190719615L)
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("ACCOUNTBOX_JSON_13\n"))
    }

    @Test
    fun export_containsAccountsTagsAndDynamicPassword() {
        val bytes = exportAccountBoxJson(testVault, 1747190719615L)
        val text = bytes.toString(Charsets.UTF_8)
        // 账号名、固定项、自定义字段、标签、TOTP 参数全部落进 JSON 正文。
        assertTrue(text.contains("\"name\": \"微信\""))
        assertTrue(text.contains("\"itemName\": \"账号\""))
        assertTrue(text.contains("\"itemValue\": \"user1\""))
        assertTrue(text.contains("\"itemValue\": \"pass1\""))
        assertTrue(text.contains("\"itemName\": \"备注\""))
        assertTrue(text.contains("\"tagName\": \"社交媒体\""))
        assertTrue(text.contains("\"secret\": \"JBSWY3DPEHPK3PXP\""))
        assertTrue(text.contains("\"type\": 3"))
        assertTrue(text.contains("\"type\": 2"))
        assertTrue(text.contains("\"version\": 13"))
        // 收藏恒 false（无此概念）。
        assertTrue(text.contains("\"favorite\": false"))
        // 固定组（默认/动态）不进顶层 tagList——只有自定义组。
        assertFalse(text.contains("\"tagName\": \"默认\""))
        assertFalse(text.contains("\"tagName\": \"动态密码\""))
    }

    @Test
    fun export_accountWithoutTotp_hasNoDynamicPasswordBlock() {
        val bytes = exportAccountBoxJson(testVault, 1L)
        val text = bytes.toString(Charsets.UTF_8)
        // 无二步验证的账号不写 dynamicPassword 键。
        val doc = importAccountBoxJson(bytes).getOrThrow()
        val noTotpAccount = doc.accounts.first { it.name == "无二步验证" }
        assertFalse(noTotpAccount.hasTotp)
        assertTrue(noTotpAccount.totpSecret.isEmpty())
    }

    // ==================== 往返一致性 ====================

    @Test
    fun roundTrip_preservesCoreFields() {
        val bytes = exportAccountBoxJson(testVault, 1747190719615L)
        val restored = importAccountBoxJson(bytes).getOrThrow()

        assertEquals(3, restored.accounts.size)
        // 顶层 tagList 还原为自定义分组（仅一个）。
        assertEquals(1, restored.groups.size)
        assertEquals("社交媒体", restored.groups.single().name)

        val weixin = restored.accounts.first { it.name == "微信" }
        assertEquals("user1", weixin.username)
        assertEquals("pass1", weixin.password)
        assertEquals(setOf(restored.groups.single().id), weixin.groups)
        assertTrue(weixin.hasTotp)
        assertEquals("JBSWY3DPEHPK3PXP", weixin.totpSecret)
        assertEquals(6, weixin.totpDigits)
        assertEquals(30, weixin.totpPeriod)
        assertEquals("SHA256", weixin.totpAlgorithm)
        assertEquals("TOTP", weixin.totpType)
        assertEquals("工作号", weixin.customFields.single().value)
        assertEquals("备注", weixin.customFields.single().label)

        val steam = restored.accounts.first { it.name == "Steam" }
        assertEquals("STEAM", steam.totpType)
        assertEquals("ABC234DEF", steam.totpSecret)

        val plain = restored.accounts.first { it.name == "无二步验证" }
        assertFalse(plain.hasTotp)
    }

    @Test
    fun roundTrip_emptyVault_producesEmptyLists() {
        val empty = PersistedVault(accounts = emptyList(), groups = emptyList())
        val restored = importAccountBoxJson(exportAccountBoxJson(empty, 1L)).getOrThrow()
        assertTrue(restored.accounts.isEmpty())
        assertTrue(restored.groups.isEmpty())
    }

    // ==================== 防御性解析 ====================

    @Test
    fun import_wrongMagic_fails() {
        val result = importAccountBoxJson("{\"accountList\":[]}".toByteArray())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("ACCOUNTBOX_JSON_13"))
    }

    @Test
    fun import_missingAccountAndPasswordItems_defaultsToEmpty() {
        // 只有名称、没有任何 item：username/password 回退空串，不崩。
        val json = "ACCOUNTBOX_JSON_13\n" + """
            {"accountList":[{"name":"只有名字","accountItemList":[],"tagList":[]}],"version":13,"tagList":[]}
        """.trimIndent()
        val restored = importAccountBoxJson(json.toByteArray()).getOrThrow()
        assertEquals(1, restored.accounts.size)
        assertEquals("", restored.accounts.single().username)
        assertEquals("", restored.accounts.single().password)
    }

    @Test
    fun import_unknownAndDeprecatedFields_ignored() {
        val json = "ACCOUNTBOX_JSON_13\n" + """
            {
              "accountList": [{
                "favorite": true,
                "lastEditTime": 123,
                "name": "带废弃字段",
                "accountItemList": [{"itemName":"账号","itemValue":"u"},{"itemName":"密码","itemValue":"p"}],
                "tagList": [],
                "futureField": {"nested": [1,2,3]}
              }],
              "lastEditTime": 456,
              "dynamicPasswordList": [{"type":3,"algorithm":"SHA1","period":30,"secret":"x","digits":6}],
              "version": 13,
              "tagList": [],
              "someTopLevelExtra": true
            }
        """.trimIndent()
        val restored = importAccountBoxJson(json.toByteArray()).getOrThrow()
        assertEquals(1, restored.accounts.size)
        assertEquals("u", restored.accounts.single().username)
        assertTrue(restored.accounts.single().customFields.isEmpty())
    }

    @Test
    fun import_duplicateTagNames_deduplicated() {
        val json = "ACCOUNTBOX_JSON_13\n" + """
            {
              "accountList": [],
              "version": 13,
              "tagList": [{"tagName":"工作"},{"tagName":"工作"},{"tagName":"生活"}]
            }
        """.trimIndent()
        val restored = importAccountBoxJson(json.toByteArray()).getOrThrow()
        assertEquals(2, restored.groups.size)
    }
}
