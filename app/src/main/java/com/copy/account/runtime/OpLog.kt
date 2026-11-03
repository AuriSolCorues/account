package com.copy.account.runtime

import android.content.Context
import com.copy.account.platform.JsonlLog
import org.json.JSONObject
import java.io.File

object LogEvents {
    const val SYNC = "sync"
    const val ACCOUNT_ADD = "account_add"
    const val ACCOUNT_DELETE = "account_delete"
    const val CRASH = "crash"
    const val EXPORT = "export"
    const val EXTENSION = "extension"
    const val AUTOFILL = "autofill"
}

data class LogEntry(
    val timeMs: Long,
    val type: String,
    val summary: String,
    val details: List<String> = emptyList()
) {
    fun toJson(): String = JSONObject().apply {
        put("timeMs", timeMs)
        put("type", type)
        put("summary", summary)
        put("details", org.json.JSONArray(details))
    }.toString()

    companion object {
        fun fromJson(line: String): LogEntry? = runCatching {
            val json = JSONObject(line)
            val details = mutableListOf<String>()
            val arr = json.optJSONArray("details")
            for (i in 0 until (arr?.length() ?: 0)) details += arr!!.getString(i)
            LogEntry(json.optLong("timeMs"), json.optString("type"), json.optString("summary"), details)
        }.getOrNull()
    }
}

private fun logFile(context: Context): File = File(context.filesDir, "logs/app-log.jsonl")

private fun log(context: Context): JsonlLog =
    JsonlLog(logFile(context), maxBytes = 1024 * 1024L, keepLines = 2000)

fun appendLog(context: Context, type: String, summary: String, details: List<String> = emptyList()) {
    log(context).append(LogEntry(System.currentTimeMillis(), type, summary, details).toJson())
}

fun readLogs(context: Context): List<LogEntry> =
    log(context).readAll().mapNotNull { LogEntry.fromJson(it) }

fun clearLogs(context: Context) {
    log(context).clear()
}
