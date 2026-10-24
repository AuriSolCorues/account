/**
 * 职责：全应用操作日志（审计设施）——扩展上报事件、账号增删、软件崩溃等事件的追加写与读取。
 *       存储在 data 家目录（context.filesDir/logs/app-log.jsonl，主体私有沙箱）：
 *       不进共享存储（无权限问题）、卸载即清、崩溃也只丢最后一行（一行一事件 JSON Lines），
 *       超限自动轮转（JsonlLog：1MB 硬顶只留最新）。扩展侧同步事件经桥 appendLog
 *       上投收编（2026-10-23 起）：本体日志 = 唯一审计出口；扩展沙箱另留本地副本仅供 adb 排障。
 * 架构位置：runtime/ 层；LogScreen（设置页「操作日志」二级页）消费，AccountApp 各事件点
 *           与 ExtensionBridgeProvider.handleAppendLog（扩展上投入口）写入。
 * Python 类比：≈ open("log.jsonl","a")——每行一个 JSON，读时倒序展示。
 */
package com.copy.account.runtime

import android.content.Context
import com.copy.account.platform.JsonlLog
import org.json.JSONObject
import java.io.File

/** 事件类型（type 字段）。 */
object LogEvents {
    const val SYNC = "sync"           // 扩展上报的会话事件（含逐文件明细）
    const val ACCOUNT_ADD = "account_add"
    const val ACCOUNT_DELETE = "account_delete"
    const val CRASH = "crash"         // 未捕获异常
    const val EXPORT = "export"       // 导出备份
    const val EXTENSION = "extension" // 扩展推来的未白名单事件（appendLog 白名单化兜底标签）
}

/** 单条日志事件。 */
data class LogEntry(
    val timeMs: Long,
    val type: String,
    /** 一行摘要（列表页展示）。 */
    val summary: String,
    /** 明细行（同步的逐文件清单等）；可为空。 */
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

/** 日志文件（filesDir 家目录，主体私有；带上限轮转——1MB 硬顶，只留最新 2000 行）。 */
private fun logFile(context: Context): File = File(context.filesDir, "logs/app-log.jsonl")

private fun log(context: Context): JsonlLog =
    JsonlLog(logFile(context), maxBytes = 1024 * 1024L, keepLines = 2000)

/** 追加一条事件（IO 线程调用方自选；写入失败静默——日志设施自身不能成为故障源）。 */
fun appendLog(context: Context, type: String, summary: String, details: List<String> = emptyList()) {
    log(context).append(LogEntry(System.currentTimeMillis(), type, summary, details).toJson())
}

/** 读全部日志（新在前）；损坏行跳过。页面在 IO 线程调用。 */
fun readLogs(context: Context): List<LogEntry> =
    log(context).readAll().mapNotNull { LogEntry.fromJson(it) }

/** 清空日志（设置页危险操作）。 */
fun clearLogs(context: Context) {
    log(context).clear()
}
