package com.copy.account.security

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPayloadTest {

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
        assertEquals("微信", parsed?.name)
        assertEquals("", parsed?.username)
        assertEquals("", parsed?.password)
    }

    @Test
    fun parse_special_characters() {
        val payload = ImportPayload(
            name = "邮箱&网站",
            username = "a+b@c.com",
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

    @Test
    fun parse_rejects_otpauth_and_other_schemes() {
        assertNull(parseImportPayload("otpauth://totp/GitHub?secret=ABC"))
        assertNull(parseImportPayload("https://example.com/?name=x"))
        assertNull(parseImportPayload("accountbox-import://totp?name=x"))
        assertNull(parseImportPayload(""))
        assertNull(parseImportPayload("   "))
    }

    @Test
    fun parse_rejects_missing_name() {
        assertNull(parseImportPayload("accountbox-import://p?username=only"))
        assertNull(parseImportPayload("accountbox-import://p?name="))
        assertNull(parseImportPayload("accountbox-import://p?name=%20%20"))
    }

    @Test
    fun parse_rejects_no_query() {
        assertNull(parseImportPayload("accountbox-import://p"))
        assertNull(parseImportPayload("accountbox-import://p?"))
    }

    @Test
    fun parse_truncates_oversized_fields() {
        val longName = "n".repeat(500)
        val parsed = parseImportPayload("accountbox-import://p?name=$longName")
        assertEquals(100, parsed?.name?.length)
        val longNotes = "x".repeat(5000)
        val parsed2 = parseImportPayload("accountbox-import://p?name=a&notes=$longNotes")
        assertEquals(2000, parsed2?.notes?.length)
    }

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
        assertTrue(json.contains("\"credentialId\""))
        assertTrue(json.contains("\"rpId\""))
        assertTrue(json.contains("\"signCount\""))
    }

    @Test
    fun registry_entry_list_roundtrip_and_unknown_keys_ignored() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val entries = listOf(
            PasskeyRegistryEntry(credentialId = "a", rpId = "github.com", userHandle = "u1", accountId = "acc-1", signCount = 1, createdAt = 100),
            PasskeyRegistryEntry(credentialId = "b", rpId = "google.com", userHandle = "u2", accountId = "", signCount = 0, createdAt = 200)
        )
        val encoded = json.encodeToString(ListSerializer(PasskeyRegistryEntry.serializer()), entries)
        val withFutureField = encoded.replace("\"signCount\":1,", "\"signCount\":1,\"futureField\":42,")
        val decoded = json.decodeFromString(ListSerializer(PasskeyRegistryEntry.serializer()), withFutureField)
        assertEquals(2, decoded.size)
        assertEquals(1L, decoded[0].signCount)
    }
}
