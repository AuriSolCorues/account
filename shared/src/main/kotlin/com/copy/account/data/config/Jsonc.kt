/** 职责：JSONC 注释剥离（纯字符串状态机，零依赖）——本体设置存储与 shared-ui 主题解析共用。
 *  架构位置：随 ui/theme 从本体 AppSettingsStore.kt 迁入共享源码集，包名不变。 */
package com.copy.account.data.config

/** 移除 JSONC 的行注释和块注释，同时保留字符串里的斜杠。 */
internal fun stripJsonComments(source: String): String {
    val out = StringBuilder(source.length)
    var inString = false
    var escaped = false
    var block = false
    var line = false
    var i = 0
    // 单指针状态机，四个互斥状态：行注释内/块注释内/字符串内/普通文本。
    // 只丢弃注释字符；字符串字面量里的双斜杠与转义引号原样保留（escaped 标志防误判字符串边界）。
    while (i < source.length) {
        val c = source[i]
        val next = source.getOrNull(i + 1)
        if (line) {
            if (c == '\n') { line = false; out.append(c) }
        } else if (block) {
            if (c == '*' && next == '/') { block = false; i++ }
        } else if (!inString && c == '/' && next == '/') {
            line = true; i++
        } else if (!inString && c == '/' && next == '*') {
            block = true; i++
        } else {
            out.append(c)
            if (c == '"' && !escaped) inString = !inString
            escaped = c == '\\' && !escaped
            if (c != '\\') escaped = false
        }
        i++
    }
    return out.toString()
}
