package com.copy.account.security

import java.security.Signature

internal const val COSE_ALG_ES256 = -7

internal sealed class CborValue {
    data class Unsigned(val v: Long) : CborValue()
    data class Negative(val v: Long) : CborValue()
    data class Bytes(val v: ByteArray) : CborValue() {
        override fun equals(other: Any?) = other is Bytes && v.contentEquals(other.v)
        override fun hashCode() = v.contentHashCode()
    }
    data class Text(val v: String) : CborValue()
    data class Array(val items: kotlin.collections.List<CborValue>) : CborValue()
    data class Map(val entries: kotlin.collections.List<Pair<CborValue, CborValue>>) : CborValue()
}

internal fun cborEncode(value: CborValue): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    cborWrite(value, out)
    return out.toByteArray()
}

private fun cborWrite(value: CborValue, out: java.io.ByteArrayOutputStream) {
    when (value) {
        is CborValue.Unsigned -> cborHead(0, value.v, out)
        is CborValue.Negative -> cborHead(1, -1 - value.v, out)
        is CborValue.Bytes -> {
            cborHead(2, value.v.size.toLong(), out)
            out.write(value.v)
        }
        is CborValue.Text -> {
            val utf8 = value.v.toByteArray(Charsets.UTF_8)
            cborHead(3, utf8.size.toLong(), out)
            out.write(utf8)
        }
        is CborValue.Array -> {
            cborHead(4, value.items.size.toLong(), out)
            value.items.forEach { cborWrite(it, out) }
        }
        is CborValue.Map -> {
            cborHead(5, value.entries.size.toLong(), out)
            value.entries.forEach { (k, v) ->
                cborWrite(k, out)
                cborWrite(v, out)
            }
        }
    }
}

private fun cborHead(major: Int, value: Long, out: java.io.ByteArrayOutputStream) {
    val m = major shl 5
    when {
        value < 24 -> out.write(m or value.toInt())
        value < 0x100 -> {
            out.write(m or 24)
            out.write(value.toInt())
        }
        value < 0x10000 -> {
            out.write(m or 25)
            out.write((value ushr 8).toInt())
            out.write((value and 0xff).toInt())
        }
        value < 0x100000000L -> {
            out.write(m or 26)
            repeat(4) { shift -> out.write((value ushr (8 * (3 - shift))).toInt()) }
        }
        else -> {
            out.write(m or 27)
            repeat(8) { shift -> out.write((value ushr (8 * (7 - shift))).toInt()) }
        }
    }
}

internal fun cborDecode(bytes: ByteArray): CborValue = CborReader(bytes).readValue()

private class CborReader(private val bytes: ByteArray) {
    private var pos = 0

    fun readValue(): CborValue {
        val initial = nextByte().toInt() and 0xff
        val major = initial ushr 5
        val info = initial and 0x1f
        val argument: Long = when {
            info < 24 -> info.toLong()
            info == 24 -> nextByte().toLong() and 0xff
            info == 25 -> {
                var v = 0L
                repeat(2) { v = (v shl 8) or (nextByte().toLong() and 0xff) }
                v
            }
            info == 26 -> {
                var v = 0L
                repeat(4) { v = (v shl 8) or (nextByte().toLong() and 0xff) }
                v
            }
            info == 27 -> {
                var v = 0L
                repeat(8) { v = (v shl 8) or (nextByte().toLong() and 0xff) }
                v
            }
            else -> throw IllegalArgumentException("CBOR info=$info 不支持")
        }
        return when (major) {
            0 -> CborValue.Unsigned(argument)
            1 -> CborValue.Negative(-1 - argument)
            2 -> CborValue.Bytes(nextBytes(argument.toInt()))
            3 -> CborValue.Text(String(nextBytes(argument.toInt()), Charsets.UTF_8))
            4 -> {
                val items = ArrayList<CborValue>(argument.toInt())
                repeat(argument.toInt()) { items += readValue() }
                CborValue.Array(items)
            }
            5 -> {
                val entries = ArrayList<Pair<CborValue, CborValue>>(argument.toInt())
                repeat(argument.toInt()) { entries += readValue() to readValue() }
                CborValue.Map(entries)
            }
            7 -> CborValue.Unsigned(argument)
            else -> throw IllegalArgumentException("CBOR major=$major 不支持")
        }
    }

    private fun nextByte(): Byte {
        if (pos >= bytes.size) throw IllegalArgumentException("CBOR 越界")
        return bytes[pos++]
    }

    private fun nextBytes(n: Int): ByteArray {
        if (pos + n > bytes.size) throw IllegalArgumentException("CBOR 越界")
        val out = bytes.copyOfRange(pos, pos + n)
        pos += n
        return out
    }
}

internal fun coseEs256PublicKey(x: ByteArray, y: ByteArray): CborValue = CborValue.Map(
    listOf(
        CborValue.Unsigned(1) to CborValue.Unsigned(2),
        CborValue.Unsigned(3) to CborValue.Negative(COSE_ALG_ES256.toLong()),
        CborValue.Negative(-1) to CborValue.Unsigned(1),
        CborValue.Negative(-2) to CborValue.Bytes(x),
        CborValue.Negative(-3) to CborValue.Bytes(y)
    )
)

internal fun attestationAuthData(cborAttestation: ByteArray): ByteArray =
    when (val decoded = cborDecode(cborAttestation)) {
        is CborValue.Map -> decoded.entries
            .firstOrNull { it.first is CborValue.Text && (it.first as CborValue.Text).v == "authData" }
            ?.second.let { it as? CborValue.Bytes }?.v
            ?: error("attestationObject 无 authData 字段")
        else -> error("attestationObject 不是 CBOR map")
    }

internal fun patchSignCount(authData: ByteArray, signCount: Int): ByteArray {
    require(authData.size >= 37) { "authData 须 ≥37 字节，实际 ${authData.size}" }
    val out = authData.copyOf()
    out[33] = (signCount ushr 24).toByte()
    out[34] = (signCount ushr 16).toByte()
    out[35] = (signCount ushr 8).toByte()
    out[36] = signCount.toByte()
    return out
}

internal fun es256RawToDer(raw: ByteArray): ByteArray {
    require(raw.size == 64) { "ES256 raw 签名须 64 字节，实际 ${raw.size}" }
    val rDer = derInteger(raw.copyOfRange(0, 32))
    val sDer = derInteger(raw.copyOfRange(32, 64))
    val out = java.io.ByteArrayOutputStream()
    out.write(0x30)
    out.write(rDer.size + sDer.size)
    out.write(rDer)
    out.write(sDer)
    return out.toByteArray()
}

private fun derInteger(bytes: ByteArray): ByteArray {
    var start = 0
    while (start < bytes.size - 1 && bytes[start].toInt() == 0) start++
    val trimmed = bytes.copyOfRange(start, bytes.size)
    val needsPad = trimmed[0].toInt() and 0x80 != 0
    val out = java.io.ByteArrayOutputStream()
    out.write(0x02)
    out.write(trimmed.size + if (needsPad) 1 else 0)
    if (needsPad) out.write(0x00)
    out.write(trimmed)
    return out.toByteArray()
}

internal data class WebAuthnRequest(
    val rpId: String,
    val challengeBase64Url: String,
    val userName: String?,
    val userIdBase64Url: String?,
    val originHint: String?,
    val pubKeyCredParams: List<Int> = emptyList(),
    val allowCredentialIds: List<String> = emptyList(),
    val requestJson: String = ""
)

internal fun parseWebAuthnRequest(json: String): WebAuthnRequest? = runCatching {
    val rpId = jsonExtractString(json, "rpId")
        ?: jsonExtractString(json, "id", section = "rp")
        ?: jsonExtractString(json, "id", section = "relayingParty")
        ?: return@runCatching null
    val challenge = jsonExtractString(json, "challenge")?.takeIf { it.isNotBlank() } ?: return null
    WebAuthnRequest(
        rpId = rpId,
        challengeBase64Url = challenge,
        userName = jsonExtractString(json, "name", section = "user")?.takeIf { it.isNotBlank() },
        userIdBase64Url = jsonExtractString(json, "id", section = "user")?.takeIf { it.isNotBlank() },
        originHint = jsonExtractString(json, "origin")?.takeIf { it.isNotBlank() },
        pubKeyCredParams = jsonExtractIntList(json, "alg", section = "pubKeyCredParams"),
        allowCredentialIds = jsonExtractStringList(json, "id", section = "allowCredentials"),
        requestJson = json
    )
}.getOrNull()

internal fun jsonExtractStringList(json: String, key: String, section: String): List<String> {
    val sectionMarker = "\"" + section + "\""
    val start = json.indexOf(sectionMarker).takeIf { it >= 0 } ?: return emptyList()
    val bracketStart = json.indexOf('[', start).takeIf { it >= 0 } ?: return emptyList()
    var depth = 0
    var end = bracketStart
    while (end < json.length) {
        when (json[end]) {
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) break
            }
        }
        end++
    }
    if (depth != 0) return emptyList()
    val scope = json.substring(bracketStart, end)
    val keyMarker = "\"" + key + "\":\""
    val values = ArrayList<String>()
    var pos = 0
    while (true) {
        val keyPos = scope.indexOf(keyMarker, pos).takeIf { it >= 0 } ?: break
        val valueStart = keyPos + keyMarker.length
        val valueEnd = scope.indexOf('"', valueStart).takeIf { it >= 0 } ?: break
        values += scope.substring(valueStart, valueEnd)
        pos = valueEnd
    }
    return values
}

internal fun jsonExtractIntList(json: String, key: String, section: String): List<Int> {
    val sectionMarker = "\"" + section + "\""
    val start = json.indexOf(sectionMarker).takeIf { it >= 0 } ?: return emptyList()
    val braceStart = json.indexOf('[', start).takeIf { it >= 0 } ?: return emptyList()
    var depth = 0
    var end = braceStart
    while (end < json.length) {
        when (json[end]) {
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) break
            }
        }
        end++
    }
    if (depth != 0) return emptyList()
    val scope = json.substring(braceStart, end)
    val keyMarker = "\"" + key + "\""
    val values = ArrayList<Int>()
    var pos = 0
    while (true) {
        val keyPos = scope.indexOf(keyMarker, pos).takeIf { it >= 0 } ?: break
        val colon = scope.indexOf(':', keyPos + keyMarker.length).takeIf { it >= 0 } ?: break
        var numStart = colon + 1
        while (numStart < scope.length && !scope[numStart].isDigit() && scope[numStart] != '-') numStart++
        if (numStart >= scope.length) break
        var numEnd = numStart
        while (numEnd < scope.length && (scope[numEnd].isDigit() || scope[numEnd] == '-')) numEnd++
        val value = scope.substring(numStart, numEnd).toIntOrNull() ?: break
        values += value
        pos = numEnd
    }
    return values
}

internal fun jsonExtractString(json: String, key: String, section: String? = null): String? {
    val scope = if (section != null) {
        val sectionMarker = "\"" + section + "\""
        val start = json.indexOf(sectionMarker).takeIf { it >= 0 } ?: return null
        val braceStart = json.indexOf('{', start).takeIf { it >= 0 } ?: return null
        var depth = 0
        var end = braceStart
        while (end < json.length) {
            when (json[end]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            end++
        }
        if (depth != 0) return null
        json.substring(braceStart, end + 1)
    } else {
        json
    }
    val keyMarker = "\"" + key + "\""
    val keyPos = scope.indexOf(keyMarker).takeIf { it >= 0 } ?: return null
    val colon = scope.indexOf(':', keyPos + keyMarker.length).takeIf { it >= 0 } ?: return null
    val quoteOpen = scope.indexOf('"', colon).takeIf { it >= 0 } ?: return null
    val quoteClose = scope.indexOf('"', quoteOpen + 1).takeIf { it >= 0 } ?: return null
    return scope.substring(quoteOpen + 1, quoteClose)
}

internal fun base64Url(bytes: ByteArray): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun base64UrlDecode(text: String): ByteArray =
    java.util.Base64.getUrlDecoder().decode(text)

internal fun es256AdaptRaw(out: ByteArray): ByteArray =
    if (out.size == 64) es256RawToDer(out) else out
