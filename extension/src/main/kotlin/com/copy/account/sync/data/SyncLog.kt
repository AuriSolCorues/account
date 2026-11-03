package com.copy.account.sync.data

import android.content.Context
import com.copy.account.platform.BridgeContract
import com.copy.account.platform.JsonlLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object SyncLog {

    @Volatile private var instance: JsonlLog? = null

    private fun log(context: Context): JsonlLog =
        instance ?: synchronized(this) {
            instance ?: JsonlLog(
                File(context.applicationContext.filesDir, "logs/sync-log.jsonl"),
                maxBytes = 512 * 1024L,
                keepLines = 1000
            ).also { instance = it }
        }

    private val localLock = Any()

    private fun write(context: Context, type: String, summary: String, details: List<String> = emptyList()) {
        synchronized(localLock) {
            log(context).append(
                JSONObject()
                    .put("t", System.currentTimeMillis())
                    .put("type", type)
                    .put("summary", summary)
                    .put("details", JSONArray(details))
                    .toString()
            )
        }
        runCatching {
            val b = android.os.Bundle().apply {
                putString(BridgeContract.KEY_LOG_TYPE, type)
                putString(BridgeContract.KEY_LOG_SUMMARY, summary)
                if (details.isNotEmpty()) putStringArrayList(BridgeContract.KEY_LOG_DETAILS, ArrayList(details))
            }
            android.net.Uri.parse("content://" + BridgeContract.AUTHORITY).let { authority ->
                context.applicationContext.contentResolver.call(authority, BridgeContract.METHOD_APPEND_LOG, null, b)
            }
        }
    }

    fun sessionStart(context: Context, mode: String, range: String, remoteDir: String) {
        write(context, "sync", "同步开始：$mode · ${rangeLabel(range)} · $remoteDir")
    }

    fun sessionEnd(context: Context, summary: String, failed: Boolean? = null) {
        val isFailed = failed ?: (summary.startsWith("失败于") || summary.startsWith("异常中止"))
        write(context, "sync", if (isFailed) "同步失败——$summary" else "同步完成：$summary")
    }

    fun error(context: Context, message: String) {
        write(context, "sync", "同步错误：$message")
    }

    private fun rangeLabel(range: String) = when (range) {
        "Backup" -> "密码文件"
        "OtherFiles" -> "其他文件"
        else -> range
    }

    fun readAll(context: Context): List<String> = log(context).readAll()

    fun clear(context: Context) = log(context).clear()
}
