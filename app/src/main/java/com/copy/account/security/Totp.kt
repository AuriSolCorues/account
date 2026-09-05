package com.copy.account.security

import android.net.Uri
import android.util.Base64
import com.copy.account.data.model.Account
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets

internal fun normalizedTotpSecret(raw: String): String {
    val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull()
    return if (uri?.scheme == "otpauth") {
        uri.getQueryParameter("secret") ?: uri.getQueryParameter("shared_secret") ?: raw
    } else raw
}

internal fun decodeSecret(raw: String, steam: Boolean): ByteArray {
    val clean = raw.trim().replace(" ", "")
    if (steam && clean.matches(Regex("[A-Za-z0-9+/]+=*"))) {
        runCatching { Base64.decode(clean, Base64.DEFAULT) }.getOrNull()?.let { if (it.isNotEmpty()) return it }
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
    return runCatching {
        val steam = account.totpType.equals("STEAM", ignoreCase = true)
        val period = account.totpPeriod.coerceAtLeast(1)
        val secret = decodeSecret(normalizedTotpSecret(account.totpSecret), steam)
        if (secret.isEmpty()) return@runCatching "------"
        if (steam) return@runCatching steamGuardCode(secret, nowMillis, period)
        val hotp = account.totpType.equals("HOTP", ignoreCase = true)
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
