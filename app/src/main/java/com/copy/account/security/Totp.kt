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

internal val Account.isHotp: Boolean get() = totpType.equals("HOTP", ignoreCase = true)

internal val Account.isSteam: Boolean get() = totpType.equals("STEAM", ignoreCase = true)

internal data class OtpAuthParams(
    val algorithm: String?,
    val digits: Int?,
    val counter: Long?,
    val period: Int?,
    val type: String?
)

internal fun parseOtpAuth(raw: String): OtpAuthParams? {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() != "otpauth") return null
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

private fun URI.queryParameter(name: String): String? = rawQuery
    ?.split('&')
    ?.firstOrNull { it.substringBefore('=') == name }
    ?.substringAfter('=', "")
    ?.let { URLDecoder.decode(it, Charsets.UTF_8) }

internal fun decodeSecret(raw: String, steam: Boolean): ByteArray {
    val clean = raw.trim().replace(" ", "")
    if (steam && clean.matches(Regex("[A-Za-z0-9+/]+=*"))) {
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
    val offset = hash[19].toInt() and 0x0f
    var value = ((hash[offset].toInt() and 0x7f) shl 24) or
        ((hash[offset + 1].toInt() and 0xff) shl 16) or
        ((hash[offset + 2].toInt() and 0xff) shl 8) or
        (hash[offset + 3].toInt() and 0xff)
    val alphabet = "23456789BCDFGHJKMNPQRTVWXY"
    val code = StringBuilder(5)
    repeat(5) {
        code.append(alphabet[value % alphabet.length])
        value = value / alphabet.length
    }
    return code.toString()
}

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

internal fun totpCode(account: Account, nowMillis: Long): String {
    if (!account.hasTotp || account.totpSecret.isBlank()) return "------"
    val steam = account.isSteam
    return runCatching {
        totpCode(account, decodeSecret(normalizedTotpSecret(account.totpSecret), steam), nowMillis)
    }.getOrDefault("------")
}

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
