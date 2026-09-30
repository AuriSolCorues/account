/**
 * 职责：两步验证码计算——标准 TOTP（RFC 6238）、HOTP 事件码（RFC 4226）、Steam Guard 变体，
 *       外加 otpauth:// 链接解析与 Base32/Base64 密钥解码。纯函数，无状态无 IO、无 Android 依赖，
 *       可用 RFC 标准向量做纯 JVM 单测。
 * 架构位置：HomeScreen/AccountDetailScreen/AccountPreviewSheet 周期调 totpCode 刷新验证码；
 *           编辑页扫码或粘贴密钥后调 parseOtpAuth 自动带出参数。
 * Python 类比：核心一步 ≈ hmac.new(secret, counter.to_bytes(8), hashlib.sha256).digest()——
 *           把「时间片或计数器」当消息做 HMAC，再从摘要里截一段数字；标准库 hmac/hashlib 可复算验证。
 */
package com.copy.account.security

import com.copy.account.data.model.Account
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun normalizedTotpSecret(raw: String): String {
    val uri = runCatching { URI(raw.trim()) }.getOrNull()
    return if (uri?.scheme?.lowercase() == "otpauth") {
        uri.queryParameter("secret") ?: uri.queryParameter("shared_secret") ?: raw
    } else raw
}

/** HOTP 事件型验证码：码只随计数器变、复制后 +1，无时间倒计时。 */
internal val Account.isHotp: Boolean get() = totpType.equals("HOTP", ignoreCase = true)

/** Steam Guard 专用 5 字符验证码（Base64 密钥、SHA1、固定周期）。 */
internal val Account.isSteam: Boolean get() = totpType.equals("STEAM", ignoreCase = true)

/** otpauth:// 链接解析出的两步验证参数；非 otpauth 串返回 null。 */
internal data class OtpAuthParams(
    val algorithm: String?,
    val digits: Int?,
    val counter: Long?,
    val period: Int?,
    /** 提供方式；host 未知时为 null（保持现有类型不动）。 */
    val type: String?
)

internal fun parseOtpAuth(raw: String): OtpAuthParams? {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() != "otpauth") return null
    // 提供方式：otpauth 的 host 即类型（totp/steam/hotp）；老款 Steam 常写在 issuer/path。
    val host = uri.host.orEmpty().lowercase()
    val looksSteam = host == "steam" || host == "totp" && (
        uri.path.orEmpty().contains("steam", ignoreCase = true) ||
            uri.queryParameter("issuer").orEmpty().contains("steam", ignoreCase = true)
        )
    return OtpAuthParams(
        algorithm = uri.queryParameter("algorithm")?.uppercase()?.replace("-", "")?.takeIf { it in setOf("SHA1", "SHA256", "SHA512") },
        digits = uri.queryParameter("digits")?.toIntOrNull()?.takeIf { it in 1..10 },
        counter = uri.queryParameter("counter")?.toLongOrNull()?.takeIf { it >= 0 },
        period = uri.queryParameter("period")?.toIntOrNull()?.takeIf { it in 1..300 },
        type = when {
            host == "hotp" -> "HOTP"
            looksSteam -> "STEAM"
            host == "totp" -> "TOTP"
            else -> null
        }
    )
}

/**
 * java.net.URI 没有 getQueryParameter；手写对齐 android.net.Uri 的行为——
 * query 按 & 切分后找首个同名键，值做 URL 解码（+ 与 %20 都还原为空格）。
 * URI 构造器对非法输入抛 URISyntaxException，调用方 runCatching 兜底（等价 Uri.parse 永不抛）。
 */
private fun URI.queryParameter(name: String): String? = rawQuery
    ?.split('&')
    ?.firstOrNull { it.substringBefore('=') == name }
    ?.substringAfter('=', "")
    ?.let { URLDecoder.decode(it, Charsets.UTF_8) }

internal fun decodeSecret(raw: String, steam: Boolean): ByteArray {
    val clean = raw.trim().replace(" ", "")
    if (steam && clean.matches(Regex("[A-Za-z0-9+/]+=*"))) {
        // Mime 解码器宽容换行，行为对齐 android.util.Base64.DEFAULT（Steam 密钥通常单行，双保险）。
        runCatching { Base64.getMimeDecoder().decode(clean) }.getOrNull()?.let { if (it.isNotEmpty()) return it }
    }
    return decodeBase32(clean)
}

private fun steamGuardCode(secret: ByteArray, nowMillis: Long, period: Int): String {
    val counter = nowMillis / 1000L / period.coerceAtLeast(1)
    val message = ByteArray(8)
    for (index in 7 downTo 0) message[index] = (counter ushr ((7 - index) * 8)).toByte()
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(secret, "HmacSHA1"))
    val hash = mac.doFinal(message)
    // 动态截断（RFC 4226 标准步骤）：取摘要最后一字节低 4 位当偏移，从该处拼 4 字节成 31-bit 整数。
    val offset = hash[19].toInt() and 0x0f
    var value = ((hash[offset].toInt() and 0x7f) shl 24) or
        ((hash[offset + 1].toInt() and 0xff) shl 16) or
        ((hash[offset + 2].toInt() and 0xff) shl 8) or
        (hash[offset + 3].toInt() and 0xff)
    // Steam 专用 26 字母表（剔除易混淆的 A/E/I/L/O/S/U/Z 等），对整数反复取模逐位出 5 字符码。
    val alphabet = "23456789BCDFGHJKMNPQRTVWXY"
    val code = StringBuilder(5)
    repeat(5) {
        code.append(alphabet[value % alphabet.length])
        value = value / alphabet.length
    }
    return code.toString()
}

// 手写 Base32（RFC 4648 字母表）：每字符 5 bit 攒进缓冲，攒满 8 bit 吐一个字节。
// ≈ Python base64.b32decode，但额外容忍小写与混入的空白字符。
private fun decodeBase32(input: String): ByteArray {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val clean = input.uppercase().filter { it in alphabet }
    var buffer = 0
    var bits = 0
    val output = ArrayList<Byte>()
    clean.forEach { char ->
        buffer = (buffer shl 5) or alphabet.indexOf(char)
        bits += 5
        if (bits >= 8) {
            bits -= 8
            output += ((buffer shr bits) and 0xff).toByte()
        }
    }
    return output.toByteArray()
}

// 便捷重载：每次调用现解一次密钥；低频场景用它。高频刷新（速览页）用下面缓存了密钥字节的重载，
// 每秒只付一次 HMAC 成本。两者输入输出完全一致。
internal fun totpCode(account: Account, nowMillis: Long): String {
    if (!account.hasTotp || account.totpSecret.isBlank()) return "------"
    val steam = account.isSteam
    return runCatching {
        totpCode(account, decodeSecret(normalizedTotpSecret(account.totpSecret), steam), nowMillis)
    }.getOrDefault("------")
}

/** 已解出密钥字节的重载：调用方缓存解码结果，秒级刷新只付 HMAC 成本。 */
internal fun totpCode(account: Account, secret: ByteArray, nowMillis: Long): String {
    if (secret.isEmpty()) return "------"
    return runCatching {
        val steam = account.isSteam
        val period = account.totpPeriod.coerceAtLeast(1)
        if (steam) return@runCatching steamGuardCode(secret, nowMillis, period)
        val hotp = account.isHotp
        val algorithm = when (account.totpAlgorithm.uppercase().replace("-", "")) {
            "SHA256" -> "SHA256"
            "SHA512" -> "SHA512"
            else -> "SHA1"
        }
        val digits = account.totpDigits.coerceIn(1, 10)
        // HOTP 事件型：消息对应当前计数器；TOTP 对应当前时间片。
        val counter = if (hotp) account.totpCounter.coerceAtLeast(0L) else nowMillis / 1000L / period
        val message = ByteArray(8)
        for (index in 7 downTo 0) message[index] = (counter ushr ((7 - index) * 8)).toByte()
        val mac = Mac.getInstance("Hmac$algorithm")
        mac.init(SecretKeySpec(secret, "Hmac$algorithm"))
        val hash = mac.doFinal(message)
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or ((hash[offset + 1].toInt() and 0xff) shl 16) or ((hash[offset + 2].toInt() and 0xff) shl 8) or (hash[offset + 3].toInt() and 0xff)
        var modulus = 1L
        repeat(digits) { modulus *= 10L }
        (binary.toLong() % modulus).toString().padStart(digits, '0').chunked(3).joinToString(" ")
    }.getOrDefault("------")
}
