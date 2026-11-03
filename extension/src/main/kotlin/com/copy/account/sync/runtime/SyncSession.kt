package com.copy.account.sync.runtime

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.copy.account.sync.config.WebDavConfigState
import com.copy.account.sync.data.BridgeClient
import com.copy.account.sync.data.DEFAULT_REMOTE_DIR
import com.copy.account.sync.data.MODE_TEST_CONNECTION
import com.copy.account.sync.data.PlanOnlySummary
import com.copy.account.sync.data.RunOutcome
import com.copy.account.sync.data.SyncMode
import com.copy.account.sync.data.SyncRange
import com.copy.account.sync.data.SyncRunSummary
import com.copy.account.sync.data.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SyncModeName(val label: String) {
    Upload("↑上传"), Download("↓下载"), MirrorToRemote("⇧覆盖云端"), MirrorToLocal("⇩覆盖本地")
}

internal const val ACTION_BIDIRECTIONAL = "BidirectionalSync"
internal const val ACTION_MIRROR_LOCAL_NC = "MirrorToLocalNoConfirm"

internal fun actionLabel(modeName: String): String = when (modeName) {
    ACTION_BIDIRECTIONAL -> "⇄同步"
    ACTION_MIRROR_LOCAL_NC -> "⇩云端覆盖本地"
    else -> SyncModeName.entries.firstOrNull { it.name == modeName }?.label ?: modeName
}

internal data class SyncProgressUi(val phase: String, val done: Int, val total: Int, val current: String)

internal fun SyncRunSummary.ok(): Boolean = !summary.startsWith("失败于")

internal const val SYNC_FEEDBACK_MILLIS = 5000L

internal class SyncSession(
    private val context: Context,
    private val scope: CoroutineScope,
    val configState: WebDavConfigState,
) {

    var busy by mutableStateOf(false)
        private set

    var busyKind by mutableStateOf("")
        private set

    var progress by mutableStateOf<SyncProgressUi?>(null)
        private set

    var progressRangeBackup by mutableStateOf(true)
        private set

    var progressMode by mutableStateOf(SyncModeName.Upload)
        private set

    var progressActionName by mutableStateOf("")
        private set

    var pendingPlan by mutableStateOf<PlanOnlySummary?>(null)
        private set
    var pendingMode by mutableStateOf<SyncModeName?>(null)
        private set
    var pendingRangeBackup by mutableStateOf(true)
        private set

    var showResult by mutableStateOf<SyncRunSummary?>(null)
        private set

    fun progressText(p: SyncProgressUi, mode: SyncModeName): String = when {
        p.phase == "planning" -> "规划中…"
        p.total > 0 -> "${mode.label} ${p.done}/${p.total} · ${p.current}"
        else -> "${mode.label}中…"
    }

    fun progressText(p: SyncProgressUi, actionName: String): String {
        val label = actionLabel(actionName)
        return when {
            p.phase == "planning" -> "规划中…"
            p.total > 0 -> "$label ${p.done}/${p.total} · ${p.current}"
            else -> "${label}…"
        }
    }

    fun runSync(modeName: String, rangeBackup: Boolean) {
        if (busy || !configState.configured) return
        val password = configState.resolvePassword()
        if (password.isBlank()) {
            configState.onTestResult(false, "应用密码不可用：请重新输入并保存")
            return
        }
        busy = true
        busyKind = if (modeName == MODE_TEST_CONNECTION) "test" else "sync"
        pendingPlan = null
        progress = null
        progressRangeBackup = rangeBackup
        progressMode = SyncModeName.entries.firstOrNull { it.name == modeName } ?: SyncModeName.Upload
        progressActionName = modeName
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    SyncWorker(
                        context = context,
                        bridge = BridgeClient(context),
                        url = configState.url,
                        user = configState.user,
                        password = password,
                        remoteDir = DEFAULT_REMOTE_DIR,
                        range = if (rangeBackup) SyncRange.Backup else SyncRange.OtherFiles,
                        modeName = modeName,
                        planOnly = modeName != MODE_TEST_CONNECTION,
                        keepLatest = configState.keepLatest,
                        progressHook = { phase, done, total, current ->
                            progress = SyncProgressUi(phase, done, total, current)
                        }
                    ).run()
                }
            }
            busy = false
            busyKind = ""
            val result = outcome.getOrNull()
            when {
                outcome.isFailure -> {
                    val msg = outcome.exceptionOrNull()?.message ?: "同步失败"
                    if (modeName == MODE_TEST_CONNECTION) configState.onTestResult(false, msg)
                    else configState.recordSyncResult(msg)
                }
                modeName == MODE_TEST_CONNECTION -> configState.onTestResult(true, "已连通")
                result is RunOutcome.Plan -> {
                    val plan = result.plan
                    if (plan.transfer.isEmpty() && plan.delete.isEmpty()) {
                        configState.recordSyncResult(
                            if (plan.skipped > 0) "两侧已一致，无需同步（一致跳过 ${plan.skipped} 个）" else "两侧均为空，无需同步"
                        )
                    } else {
                        pendingPlan = plan
                        pendingMode = SyncModeName.entries.firstOrNull { it.name == modeName }
                        pendingRangeBackup = rangeBackup
                    }
                }
                result is RunOutcome.Summary -> {
                    if (result.summary.ok()) showResult = result.summary
                    else configState.recordSyncResult("失败——${result.summary.summary}")
                }
            }
        }
    }

    fun startConfirmRun(modeName: String, rangeBackup: Boolean) {
        if (busy) return
        val password = configState.resolvePassword()
        if (password.isBlank()) {
            configState.recordSyncResult("应用密码不可用：请重新输入并保存")
            return
        }
        busy = true
        busyKind = "sync"
        progress = null
        progressRangeBackup = rangeBackup
        progressMode = SyncModeName.entries.firstOrNull { it.name == modeName } ?: SyncModeName.Upload
        progressActionName = modeName
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    SyncWorker(
                        context = context,
                        bridge = BridgeClient(context),
                        url = configState.url,
                        user = configState.user,
                        password = password,
                        remoteDir = DEFAULT_REMOTE_DIR,
                        range = if (rangeBackup) SyncRange.Backup else SyncRange.OtherFiles,
                        modeName = modeName,
                        planOnly = false,
                        keepLatest = configState.keepLatest,
                        progressHook = { phase, done, total, current ->
                            progress = SyncProgressUi(phase, done, total, current)
                        }
                    ).run()
                }
            }
            busy = false
            busyKind = ""
            progress = null
            val result = outcome.getOrNull()
            when {
                outcome.isFailure -> configState.recordSyncResult(outcome.exceptionOrNull()?.message ?: "同步失败")
                result is RunOutcome.Summary -> {
                    val s = result.summary
                    val failed = s.summary.startsWith("失败于")
                    if (!failed) showResult = s
                    configState.recordSyncResult(if (failed) "失败——${s.summary}" else s.summary)
                }
                else -> configState.recordSyncResult("同步异常：意外产出")
            }
        }
    }

    fun dismissPlan() {
        pendingPlan = null
        pendingMode = null
    }

    fun confirmPlan() {
        val mode = pendingMode ?: return
        val rangeBackup = pendingRangeBackup
        pendingPlan = null
        pendingMode = null
        startConfirmRun(mode.name, rangeBackup)
    }

    fun dismissResult() {
        showResult?.let { configState.recordSyncResult(it.summary) }
        showResult = null
    }
}
