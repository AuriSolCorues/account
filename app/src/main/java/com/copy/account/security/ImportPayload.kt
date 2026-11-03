package com.copy.account.security

internal data class ImportPayload(
    val name: String,
    val username: String,
    val password: String,
    val url: String,
    val notes: String
)

internal fun parseImportPayload(raw: String): ImportPayload? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith(SCHEME)) return null
    val query = trimmed.removePrefix(SCHEME)
    if (query.isEmpty()) return null
    val params = mutableMapOf<String, String>()
    query.split('&').forEach { pair ->
        val key = pair.substringBefore('=')
        val value = pair.substringAfter('=', "")
        if (key.isNotEmpty() && key !in params) params[key] = percentDecode(value)
    }
    val name = params["name"]?.trim().orEmpty()
    if (name.isEmpty()) return null
    return ImportPayload(
        name = name.take(100),
        username = (params["username"] ?: "").take(200),
        password = (params["password"] ?: "").take(200),
        url = (params["url"] ?: "").take(500),
        notes = (params["notes"] ?: "").take(2000)
    )
}

internal fun buildImportPayload(payload: ImportPayload): String =
    SCHEME + listOf(
        "name" to payload.name,
        "username" to payload.username,
        "password" to payload.password,
        "url" to payload.url,
        "notes" to payload.notes
    ).joinToString("&") { (k, v) -> "$k=${percentEncode(v)}" }

private const val SCHEME = "accountbox-import://p?"

internal fun percentEncode(text: String): String = buildString {
    text.toByteArray(Charsets.UTF_8).forEach { b ->
        val c = b.toInt() and 0xff
        if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        ) {
            append(c.toChar())
        } else {
            append('%')
            append("%02X".format(c))
        }
    }
}

internal fun percentDecode(text: String): String {
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    val bytes = text.toByteArray(Charsets.ISO_8859_1)
    while (i < bytes.size) {
        val b = bytes[i].toInt() and 0xff
        when {
            b == '+'.code -> { out.write(' '.code); i++ }
            b == '%'.code && i + 2 < bytes.size -> {
                val hi = hexVal(bytes.getOrNull(i + 1))
                val lo = hexVal(bytes.getOrNull(i + 2))
                if (hi != null && lo != null) {
                    out.write((hi shl 4) or lo)
                    i += 3
                } else {
                    out.write(b)
                    i++
                }
            }
            else -> { out.write(b); i++ }
        }
    }
    return out.toString("UTF-8")
}

private fun hexVal(b: Byte?): Int? {
    if (b == null) return null
    val c = b.toInt() and 0xff
    return when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> null
    }
}
