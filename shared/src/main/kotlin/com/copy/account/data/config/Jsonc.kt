package com.copy.account.data.config

internal fun stripJsonComments(source: String): String {
    val out = StringBuilder(source.length)
    var inString = false
    var escaped = false
    var block = false
    var line = false
    var i = 0
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
