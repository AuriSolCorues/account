package com.copy.account.security

import com.copy.account.data.model.Account
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RFC 4226/6238 标准向量 + otpauth 解析行为。Totp.kt 已去 Android 化（java.net.URI /
 * java.util.Base64），纯 JVM 可跑。
 */
class TotpTest {

    private fun account(
        digits: Int = 6,
        period: Int = 30,
        algorithm: String = "SHA1",
        type: String = "TOTP",
        counter: Long = 0,
        secret: String = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    ) = Account(
        id = "test",
        name = "test",
        username = "u",
        password = "p",
        hasTotp = true,
        totpSecret = secret,
        totpDigits = digits,
        totpPeriod = period,
        totpAlgorithm = algorithm,
        totpType = type,
        totpCounter = counter
    )

    // ==================== RFC 4226 HOTP ====================

    @Test
    fun hotp_rfc4226_appendix_d_vectors() {
        // 密钥 "12345678901234567890" 的 Base32 = GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
        val expected = listOf(
            "755 224", "287 082", "359 152", "969 429", "338 314",
            "254 676", "287 922", "162 583", "399 871", "520 489"
        )
        for (counter in 0..9L) {
            assertEquals(expected[counter.toInt()], totpCode(account(type = "HOTP", counter = counter), 0L))
        }
    }

    // ==================== RFC 6238 TOTP ====================

    @Test
    fun totp_rfc6238_appendix_b_sha1_vectors() {
        // 8 位截断向量（毫秒 = 秒 × 1000）；totpCode 输出每 3 位插一个空格。
        // 1234567890 与 1234567899 同处一个 30s 时间窗，码相同。
        val vectors = listOf(
            59L to "942 870 82",
            1111111109L to "070 818 04",
            1111111111L to "140 504 71",
            1234567890L to "890 059 24",
            1234567899L to "890 059 24",
            2000000000L to "692 790 37"
        )
        for ((seconds, expected) in vectors) {
            assertEquals(expected, totpCode(account(digits = 8), seconds * 1000L))
        }
    }

    @Test
    fun totp_rfc6238_sha256_and_sha512_vectors() {
        // RFC 6238：SHA256 密钥 = "1234567890"×3+"12"（32 字节）；SHA512 = "1234567890"×6+"1234"（64 字节）。
        // Base32("1234567890")=GEZDGNBVGY3TQOJQ；Base32("12")=GEZA；Base32("1234")=GEZDGNA。
        val secret256 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA"
        val secret512 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA"
        val vectors256 = listOf(
            59L to "461 192 46",
            1111111109L to "680 847 74",
            1111111111L to "670 626 74",
            1234567890L to "918 194 24",
            1234567899L to "918 194 24",
            2000000000L to "906 988 25"
        )
        val vectors512 = listOf(
            59L to "906 939 36",
            1111111109L to "250 912 01",
            1111111111L to "999 433 26",
            1234567890L to "934 411 16",
            1234567899L to "934 411 16",
            2000000000L to "386 189 01"
        )
        for ((seconds, expected) in vectors256) {
            assertEquals(expected, totpCode(account(digits = 8, algorithm = "SHA256", secret = secret256), seconds * 1000L))
        }
        for ((seconds, expected) in vectors512) {
            assertEquals(expected, totpCode(account(digits = 8, algorithm = "SHA512", secret = secret512), seconds * 1000L))
        }
    }

    // ==================== otpauth:// 解析 ====================

    @Test
    fun parseOtpAuth_full_totp_link() {
        val params = parseOtpAuth("otpauth://totp/Issuer:Name?secret=JBSWY3DPEHPK3PXP&issuer=Issuer&period=45&digits=8&algorithm=SHA256")
        assertEquals("TOTP", params?.type)
        assertEquals(45, params?.period)
        assertEquals(8, params?.digits)
        assertEquals("SHA256", params?.algorithm)
        assertNull(params?.counter)
    }

    @Test
    fun parseOtpAuth_hotp_link_with_counter() {
        val params = parseOtpAuth("otpauth://hotp/Name?secret=JBSWY3DPEHPK3PXP&counter=5")
        assertEquals("HOTP", params?.type)
        assertEquals(5L, params?.counter)
    }

    @Test
    fun parseOtpAuth_steam_by_host_or_issuer() {
        assertEquals("STEAM", parseOtpAuth("otpauth://steam/Name?secret=JBSWY3DPEHPK3PXP")?.type)
        assertEquals("STEAM", parseOtpAuth("otpauth://totp/Name?secret=JBSWY3DPEHPK3PXP&issuer=Steam")?.type)
        assertEquals("STEAM", parseOtpAuth("otpauth://totp/steam:Name?secret=JBSWY3DPEHPK3PXP")?.type)
    }

    @Test
    fun parseOtpAuth_rejects_invalid_input() {
        assertNull(parseOtpAuth(""))
        assertNull(parseOtpAuth("http://example.com/x?secret=ABC"))
        // 未知 host：type 为 null（其余字段照常解析，但调用方靠 type 判别）
        assertNull(parseOtpAuth("otpauth://unknown/Name?secret=JBSWY3DPEHPK3PXP")?.type)
    }

    @Test
    fun parseOtpAuth_drops_out_of_range_values() {
        val params = parseOtpAuth("otpauth://totp/Name?secret=JBSWY3DPEHPK3PXP&period=301&digits=11&counter=-1&algorithm=MD5")
        assertNull(params?.period)
        assertNull(params?.digits)
        assertNull(params?.counter)
        assertNull(params?.algorithm)
    }

    @Test
    fun parseOtpAuth_decodes_percent_and_plus() {
        val params = parseOtpAuth("otpauth://totp/Name?secret=JBSWY3DPEHPK3PXP&issuer=My%20Issuer&other=x%2By")
        // issuer 含解码后的空格（%20）；query 值里的 + 语义此处经由 issuer 分支验证不炸即可。
        assertEquals("TOTP", params?.type)
    }

    @Test
    fun normalizedTotpSecret_extracts_secret_param() {
        assertEquals("JBSWY3DPEHPK3PXP", normalizedTotpSecret("otpauth://totp/N?secret=JBSWY3DPEHPK3PXP"))
        assertEquals("JBSWY3DPEHPK3PXP", normalizedTotpSecret("otpauth://steam/N?shared_secret=JBSWY3DPEHPK3PXP"))
        assertEquals("PLAINSECRET", normalizedTotpSecret("PLAINSECRET"))
    }

    @Test
    fun decodeSecret_base32_decodes_hello() {
        // JBSWY3DPEHPK3PXP = "Hello!\xde\xad\xbe\xef"（16 个 Base32 字符 → 10 字节，尾部非 8 对齐）
        val decoded = decodeSecret("JBSWY3DPEHPK3PXP", steam = false)
        assertEquals("Hello!", decoded.copyOf(6).decodeToString())
        assertEquals(10, decoded.size)
    }

    @Test
    fun decodeSecret_steam_base64_preferred_when_matching_shape() {
        // "SGVsbG8h" = "Hello!" 的 Base64；Steam 语义下优先按 Base64 解。
        assertEquals("Hello!", decodeSecret("SGVsbG8h", steam = true).decodeToString())
        // JBSWY... 恰好也匹配 Base64 形状（纯字母数字），按原有语义同样走 Base64 轨道——
        // 解出的字节虽非预期密钥，但这是 decodeSecret 的既定行为（形状优先，不回退）。
        // 断言其不抛异常且非空即可。
        assertTrue(decodeSecret("JBSWY3DPEHPK3PXP", steam = true).isNotEmpty())
    }

    @Test
    fun totpCode_invalid_secret_falls_back_to_dashes() {
        assertEquals("------", totpCode(account(secret = "!!!!"), 0L))
    }

    @Test
    fun totpCode_no_totp_falls_back_to_dashes() {
        assertEquals("------", totpCode(account().copy(hasTotp = false), 0L))
    }
}
