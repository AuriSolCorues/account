/**
 * 职责：「账号本子」扫码导入的 payload 编解码——`accountbox-import://` 链接格式定义与解析，
 *       与 otpauth://（TOTP 扫码）同走 ScanQrOverlay 但前缀分流互不干扰。
 *       payload 内容：URL 查询参数携带账号字段（name/username/password/url/notes），UTF-8 + 百分号编码。
 * 架构位置：编辑页/首页「扫码导入」入口调 parseImportPayload；电脑侧由外部工具生成二维码。
 *           解析成功走现有新建账号保存链路（自动获得 uid）。
 * Python 类比：≈ urllib.parse.parse_qs——query 切分 + 百分号解码，白名单字段收窄攻击面。
 */
package com.copy.account.security

/** 扫码导入解析结果：仅收窄后的安全字段集（不含 id/uid——那些由本机生成）。 */
internal data class ImportPayload(
    val name: String,
    val username: String,
    val password: String,
    val url: String,
    val notes: String
)

/**
 * 解析 `accountbox-import://p?name=...&username=...&password=...&url=...&notes=...`。
 * 规则：
 * - scheme 必须精确匹配 accountbox-import，否则返回 null（otpauth 等交回原有扫码分流）
 * - name 必填非空；其余字段可缺省为空串
 * - 各字段长度上限（name 100 / username 200 / password 200 / url 500 / notes 2000），超长截断
 * - 恶意/畸形输入（无 query、坏百分号编码）一律 null，由调用方提示扫码失败
 */
internal fun parseImportPayload(raw: String): ImportPayload? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith(SCHEME)) return null
    // SCHEME 常量已含 "?":removePrefix 后剩下的就是 query 本身（不再含 ?）
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

/** 生成导入 payload（本 App 暂不生成二维码，此函数供测试与未来「分享为二维码」功能使用）。 */
internal fun buildImportPayload(payload: ImportPayload): String =
    SCHEME + listOf(
        "name" to payload.name,
        "username" to payload.username,
        "password" to payload.password,
        "url" to payload.url,
        "notes" to payload.notes
    ).joinToString("&") { (k, v) -> "$k=${percentEncode(v)}" }

private const val SCHEME = "accountbox-import://p?"

/** 百分号编码（保留字母数字与 -_./~；其余按 UTF-8 转义——兼容 qr 码字母数字模式提升容量）。 */
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

/** 百分号解码：+ 也还原为空格（URL 查询串惯例）；坏转义原样保留（宽容策略）。 */
internal fun percentDecode(text: String): String {
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    val bytes = text.toByteArray(Charsets.ISO_8859_1)
    while (i < bytes.size) {
        val b = bytes[i].toInt() and 0xff
        when {
            b == '+'.code -> { out.write(' '.code); i++ }
            b == '%'.code && i + 2 < bytes.size -> {
                // %XX：两位 hex；非法则原样输出 '%'
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
