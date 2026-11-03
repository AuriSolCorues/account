package com.copy.account.platform

import java.io.File

class JsonlLog(
    private val file: File,
    private val maxBytes: Long,
    private val keepLines: Int = 2000
) {
    fun append(line: String) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.isFile && file.length() > maxBytes) rotate()
            file.appendText(line + "\n")
        }
    }

    private fun rotate() {
        val lines = file.readLines()
        val tmp = File(file.parentFile, file.name + ".tmp")
        if (lines.isEmpty()) {
            file.delete()
            return
        }
        tmp.writeText(lines.takeLast(keepLines).joinToString("\n") + "\n")
        tmp.renameTo(file)
    }

    fun readAll(): List<String> = runCatching {
        if (!file.isFile) emptyList() else file.readLines().asReversed()
    }.getOrDefault(emptyList())

    fun clear() {
        runCatching { file.delete() }
    }
}
