/**
 * 职责：明文导出（security/PlainExport.kt）的单测——往返无损、信封字段正确、特殊字符存活。
 * 架构位置：本文件只依赖纯 JVM 的导出函数与数据模型，不需要 Android 或 Robolectric。
 */
package com.copy.account.security

import com.copy.account.data.model.Account
import com.copy.account.data.model.AccountField
import com.copy.account.data.model.Group
import com.copy.account.data.model.GroupKind
import com.copy.account.data.model.PersistedVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainExportTest {

    /** 明文内容必须原样往返：密码、TOTP 密钥、隐藏自定义字段一个都不能丢或被改。 */
    @Test
    fun roundTrip_preservesEverySensitiveField() {
        val input = vault()
        val json = exportPlainJson(input, "2026-09-26 12:00:00").toString(Charsets.UTF_8)
        val doc = vaultJson.decodeFromString(PlainExportDocument.serializer(), json)

        assertEquals(input, doc.vault)
        assertEquals(input.accounts, doc.vault.accounts)
        assertEquals(input.groups, doc.vault.groups)
        // 单独点出这两个最敏感的：它们是「明文导出」相对 .acc 真正多泄漏的东西。
        assertEquals("JBSWY3DPEHPK3PXP", doc.vault.accounts[0].totpSecret)
        assertTrue("隐藏字段必须照原样导出", doc.vault.accounts[0].customFields.any { it.hidden })
    }

    @Test
    fun envelope_isSelfDescribing() {
        val doc = vaultJson.decodeFromString(
            PlainExportDocument.serializer(),
            exportPlainJson(vault(), "2026-09-26 12:00:00").toString(Charsets.UTF_8)
        )

        assertEquals("account-plaintext-export", doc.format)
        assertEquals(1, doc.formatVersion)
        assertEquals("2026-09-26 12:00:00", doc.exportedAt)
        assertEquals(1, doc.accountCount)
        assertEquals(1, doc.groupCount)        // warning 随文件走：脱离本应用后也要能一眼看出这是未加密的。
        assertEquals(PLAIN_EXPORT_WARNING, doc.warning)
        assertTrue("警告语应点明明文与密钥", PLAIN_EXPORT_WARNING.contains("明文") && PLAIN_EXPORT_WARNING.contains("密钥"))
    }

    /** 密码里什么都可能出现；JSON 必须原样装下，不能截断、不能转义坏、不能丢字符。 */
    @Test
    fun roundTrip_survivesAwkwardPasswordsAndUnicode() {
        val awkward = listOf(
            "with,comma",
            "with\"quote",
            "with\\backslash",
            "with\nnewline",
            "with\ttab",
            "  前后空格  ",
            "中文密码密码",
            "emoji🔐🔑",
            ""
        )
        val input = vault(passwords = awkward)
        val doc = vaultJson.decodeFromString(
            PlainExportDocument.serializer(),
            exportPlainJson(input, "t").toString(Charsets.UTF_8)
        )

        assertEquals(awkward, doc.vault.accounts.map { it.password })
    }

    /** 空库也要能导出成合法 JSON：备份清空后重导不该崩。 */
    @Test
    fun emptyVault_stillProducesDecodableDocument() {
        val empty = PersistedVault(accounts = emptyList(), groups = emptyList())
        val doc = vaultJson.decodeFromString(
            PlainExportDocument.serializer(),
            exportPlainJson(empty, "2026-01-01 00:00:00").toString(Charsets.UTF_8)
        )

        assertEquals(empty, doc.vault)
        assertEquals(0, doc.accountCount)
        assertEquals(0, doc.groupCount)
    }

    /** 兼容旧字段：读回时容忍未知键，明文文件被手工加过注释字段也不至于整个作废。 */
    @Test
    fun decode_toleratesUnknownKeys() {
        val json = """{"format":"account-plaintext-export","formatVersion":1,"exportedAt":"t",""" +
            """"accountCount":0,"groupCount":0,"warning":"w","vault":{"version":1,"accounts":[],"groups":[]},"未知字段":123}"""

        val doc = vaultJson.decodeFromString(PlainExportDocument.serializer(), json)

        assertEquals("account-plaintext-export", doc.format)
    }

    /** 每个密码建一个账号，这样 awkward 列表里的每一条都会真正走到往返断言里。 */
    private fun vault(passwords: List<String> = listOf("p@ssw0rd")) = PersistedVault(
        version = 1,
        accounts = passwords.mapIndexed { index, password ->
            Account(
                id = "a$index",
                name = "示例账号$index",
                username = "hello$index@example.com",
                password = password,
                hasTotp = true,
                totpSecret = "JBSWY3DPEHPK3PXP",
                customFields = listOf(
                    AccountField("f$index", "恢复码", "recovery-code", hidden = true),
                    AccountField("n$index", "网址", "https://example.com")
                )
            )
        },
        groups = listOf(Group("default", "默认", GroupKind.DEFAULT)),
        selectedGroupId = "default"
    )
}
