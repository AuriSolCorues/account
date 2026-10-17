/**
 * 职责：Phase 3 单测——① ImportPayload 编解码往返/畸形输入/otpauth 分流/长度截断/中文编码
 *       ② PasskeyRegistryEntry 的 JSON 序列化往返（registry 文件格式稳定性的守护测试）。
 * 测试策略：纯 JVM。payload 编解码是纯函数直测；PasskeyRegistry 依赖 android.content.Context
 *       （文件 IO）不在单测范围（Robolectric 可覆盖但收益低——读写逻辑薄，序列化格式才是
 *       需要锁定的部分，故用 kotlinx.serialization 直接测条目编解码）。
 */
package com.copy.account.security

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPayloadTest {

    // ==================== 合法解析 ====================

    @Test
    fun parse_full_payload_roundtrip() {
        val raw = buildImportPayload(
            ImportPayload(
                name = "GitHub",
                username = "octocat@example.com",
                password = "s3cret!",
                url = "https://github.com",
                notes = "main account"
            )
        )
        assertTrue(raw.startsWith("accountbox-import://p?"))
        val parsed = parseImportPayload(raw)
        assertEquals("GitHub", parsed?.name)
        assertEquals("octocat@example.com", parsed?.username)
        assertEquals("s3cret!", parsed?.password)
        assertEquals("https://github.com", parsed?.url)
        assertEquals("main account", parsed?.notes)
    }

    @Test
    fun parse_minimal_payload_name_only() {
        val parsed = parseImportPayload("accountbox-import://p?name=%E5%BE%AE%E4%BF%A1")
        assertEquals("微信", parsed?.name) // 百分号编码的中文
        assertEquals("", parsed?.username)
        assertEquals("", parsed?.password)
    }

    @Test
    fun parse_special_characters() {
        val payload = ImportPayload(
            name = "邮箱&网站",
            username = "a+b@c.com", // 字面 +：编码器转 %2B，解码还原 +（裸 + 才按空格解码）
            password = "p@ss%123",
            url = "",
            notes = "line1\nline2"
        )
        val parsed = parseImportPayload(buildImportPayload(payload))
        assertEquals("邮箱&网站", parsed?.name)
        assertEquals("a+b@c.com", parsed?.username)
        assertEquals("p@ss%123", parsed?.password)
        assertEquals("line1\nline2", parsed?.notes)
    }

    // ==================== 分流与畸形 ====================

    @Test
    fun parse_rejects_otpauth_and_other_schemes() {
        assertNull(parseImportPayload("otpauth://totp/GitHub?secret=ABC"))
        assertNull(parseImportPayload("https://example.com/?name=x"))
        assertNull(parseImportPayload("accountbox-import://totp?name=x")) // 子路径不是 /p
        assertNull(parseImportPayload(""))
        assertNull(parseImportPayload("   "))
    }

    @Test
    fun parse_rejects_missing_name() {
        assertNull(parseImportPayload("accountbox-import://p?username=only"))
        assertNull(parseImportPayload("accountbox-import://p?name="))
        assertNull(parseImportPayload("accountbox-import://p?name=%20%20")) // 全空白 trim 后为空
    }

    @Test
    fun parse_rejects_no_query() {
        assertNull(parseImportPayload("accountbox-import://p"))
        assertNull(parseImportPayload("accountbox-import://p?"))
    }

    // ==================== 长度截断 ====================

    @Test
    fun parse_truncates_oversized_fields() {
        val longName = "n".repeat(500)
        val parsed = parseImportPayload("accountbox-import://p?name=$longName")
        assertEquals(100, parsed?.name?.length) // name 截到 100
        val longNotes = "x".repeat(5000)
        val parsed2 = parseImportPayload("accountbox-import://p?name=a&notes=$longNotes")
        assertEquals(2000, parsed2?.notes?.length)
    }

    // ==================== percent 编解码 ====================

    @Test
    fun percent_roundtrip_unicode() {
        val text = "密码123!@#¥……&*（）——+="
        assertEquals(text, percentDecode(percentEncode(text)))
    }

    @Test
    fun percent_decode_bad_escape_kept_literal() {
        assertEquals("100%", percentDecode("100%"))
        assertEquals("%ZZ", percentDecode("%ZZ"))
        assertEquals("a%2", percentDecode("a%2"))
    }

    @Test
    fun percent_decode_plus_is_space() {
        assertEquals("hello world", percentDecode("hello+world"))
    }

    // ==================== PasskeyRegistryEntry 序列化格式守护 ====================

    @Test
    fun registry_entry_json_roundtrip() {
        val entry = PasskeyRegistryEntry(
            credentialId = "abc123",
            rpId = "github.com",
            userHandle = "u123",
            accountId = "account-123-XY12",
            signCount = 5,
            createdAt = 1_760_000_000_000
        )
        val json = Json.encodeToString(PasskeyRegistryEntry.serializer(), entry)
        val decoded = Json.decodeFromString(PasskeyRegistryEntry.serializer(), json)
        assertEquals(entry, decoded)
        // 字段名锁定（registry 文件格式向后兼容的契约）
        assertTrue(json.contains("\"credentialId\""))
        assertTrue(json.contains("\"rpId\""))
        assertTrue(json.contains("\"signCount\""))
    }

    @Test
    fun registry_entry_list_roundtrip_and_unknown_keys_ignored() {
        val entries = listOf(
            PasskeyRegistryEntry(credentialId = "a", rpId = "github.com", userHandle = "u1", accountId = "acc-1", signCount = 1, createdAt = 100),
            PasskeyRegistryEntry(credentialId = "b", rpId = "google.com", userHandle = "u2", accountId = "", signCount = 0, createdAt = 200)
        )
        val json = Json { encodeDefaults = true }
            .encodeToString(ListSerializer(PasskeyRegistryEntry.serializer()), entries)
        // 模拟未来版本加了新字段：ignoreUnknownKeys 保证向前兼容
        val withFutureField = json.replace("\"signCount\":1,", "\"signCount\":1,\"futureField\":42,")
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString(ListSerializer(PasskeyRegistryEntry.serializer()), withFutureField)
        assertEquals(2, decoded.size)
        assertEquals(1L, decoded[0].signCount)
    }
}
