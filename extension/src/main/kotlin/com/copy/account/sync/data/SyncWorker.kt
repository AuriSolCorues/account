package com.copy.account.sync.data

import android.content.Context
import org.json.JSONObject

internal enum class SyncRange { Backup, OtherFiles }

internal const val MODE_TEST_CONNECTION = "TestConnection"

internal const val MODE_BIDIRECTIONAL = "BidirectionalSync"
internal const val MODE_MIRROR_LOCAL_NC = "MirrorToLocalNoConfirm"

internal data class SyncRunSummary(
    val summary: String,
    val transferred: Int,
    val deleted: Int,
    val skipped: Int,
    val entries: List<String>
)

internal data class PlanOnlySummary(
    val transfer: List<String>,
    val delete: List<String>,
    val skipped: Int,
    val deleteRatioOverHalf: Boolean
)

internal sealed class RunOutcome {
    data class Summary(val summary: SyncRunSummary) : RunOutcome()
    data class Plan(val plan: PlanOnlySummary) : RunOutcome()
}

private const val MAX_ENTRIES = 200

internal class SyncWorker(
    @Suppress("unused") private val context: Context,
    private val bridge: BridgeClient,
    private val url: String,
    private val user: String,
    private val password: String,
    private val remoteDir: String,
    private val range: SyncRange,
    private val modeName: String,
    private val planOnly: Boolean,
    private val keepLatest: Int = 5,
    private val progressHook: (phase: String, done: Int, total: Int, current: String) -> Unit
) {

    private val progress: (phase: String, done: Int, total: Int, current: String) -> Unit

    init {
        val hook = progressHook
        progress = { phase, done, total, current ->
            checkNotLocked()
            hook(phase, done, total, current)
        }
    }

    private val mode: SyncMode by lazy { SyncMode.valueOf(modeName) }

    private fun checkNotLocked() {
        if (bridge.isLocked()) error("主体已锁定，同步中止")
    }

    fun run(): RunOutcome {
        if (url.isBlank() || user.isBlank() || password.isBlank()) error("连接配置不完整")
        SyncLog.sessionStart(context, modeName, range.name, remoteDir)
        try {
            checkNotLocked()
            return runInner().also { SyncLog.sessionEnd(context, outcomeSummary(it)) }
        } catch (t: Throwable) {
            SyncLog.error(context, t.message ?: t.javaClass.simpleName)
            SyncLog.sessionEnd(context, "异常中止：${t.message ?: t.javaClass.simpleName}")
            throw t
        }
    }

    private fun outcomeSummary(outcome: RunOutcome): String = when (outcome) {
        is RunOutcome.Summary -> outcome.summary.summary
        is RunOutcome.Plan ->
            "规划：传 ${outcome.plan.transfer.size} · 删 ${outcome.plan.delete.size} · 跳过 ${outcome.plan.skipped}"
    }

    private fun runInner(): RunOutcome {
        val client = WebDavClient(url.trim(), user.trim(), password)
        if (modeName == MODE_TEST_CONNECTION) {
            client.testConnection()
            return RunOutcome.Summary(SyncRunSummary("已连通", 0, 0, 0, emptyList()))
        }
        if (modeName == MODE_BIDIRECTIONAL || modeName == MODE_MIRROR_LOCAL_NC) {
            check(range == SyncRange.Backup) { "该动作仅限备份范围" }
            val entries = mutableListOf<String>()
            val summary = if (modeName == MODE_BIDIRECTIONAL) {
                runBidirectional(client, entries)
            } else {
                runMirrorLocalNoConfirm(client, entries)
            }
            return RunOutcome.Summary(
                SyncRunSummary(
                    summary = summary,
                    transferred = entries.count { it.startsWith("↑") || it.startsWith("↓") },
                    deleted = entries.count { it.startsWith("✕") },
                    skipped = entries.count { it.startsWith("⏭") },
                    entries = entries.take(MAX_ENTRIES)
                )
            )
        }

        val backupFiles = runCatching { bridge.list(BridgeClient.Scope.BACKUP) }
            .getOrElse { error("本地目录不可用（${it.message}）：请先在主体中导出一次备份") }
        progress("planning", 0, 0, "")

        if (planOnly) {
            val plan = when (range) {
                SyncRange.Backup -> planBackupRange(client, backupFiles)
                SyncRange.OtherFiles -> planOtherRange(client)
            }
            val targetSideTotal = plan.transfer.size + plan.delete.size + plan.skipped
            return RunOutcome.Plan(
                PlanOnlySummary(
                    transfer = plan.transfer.take(MAX_ENTRIES),
                    delete = plan.delete.take(MAX_ENTRIES),
                    skipped = plan.skipped,
                    deleteRatioOverHalf = plan.delete.size >= 3 && plan.delete.size * 2 > targetSideTotal
                )
            )
        }

        val entries = mutableListOf<String>()

        val summary = when (range) {
            SyncRange.Backup -> runBackupRange(client, backupFiles, entries)
            SyncRange.OtherFiles -> runOtherRange(client, entries)
        }
        return RunOutcome.Summary(
            SyncRunSummary(
                summary = summary,
                transferred = entries.count { it.startsWith("↑") || it.startsWith("↓") },
                deleted = entries.count { it.startsWith("✕") },
                skipped = entries.count { it.startsWith("⏭") },
                entries = entries.take(MAX_ENTRIES)
            )
        )
    }

    private fun planBackupRange(client: WebDavClient, backupFiles: List<SyncFileRecord>): SyncPlan {
        val remoteRoot = "${remoteDir.trim('/')}/account"
        progress("planning", 0, 0, remoteRoot)
        val remote = client.listDir(remoteRoot).filter { !it.isDir }.associateBy { it.name }
        return planScope(
            local = backupFiles.map { it.rel }.toSet(),
            remote = remote,
            localMtimeSec = emptyMap(),
rule = ConflictRule.SameNameSkip,
            mode = mode
        )
    }

    private fun runBackupRange(
        client: WebDavClient,
        backupFiles: List<SyncFileRecord>,
        entries: MutableList<String>
    ): String {
        val remoteRoot = "${remoteDir.trim('/')}/account"
        val plan = planBackupRange(client, backupFiles)
        if (!plan.hasWork) return "两侧已一致，无需同步"
        progress("transferring", 0, plan.transfer.size, "")
        client.mkcol(remoteRoot)
val local = bridge.list(BridgeClient.Scope.BACKUP).associateBy { it.rel }
        val remote = client.listDir(remoteRoot).filter { !it.isDir }.associateBy { it.name }
        val outcome = executePlan(plan, mode,
            transferOne = { name ->
                if (mode == SyncMode.Upload || mode == SyncMode.MirrorToRemote) {
                    local[name] ?: error("本地缺 $name")
                    client.put("$remoteRoot/$name", bridge.readBytes("account/$name"))
                    entries += "↑ $name（成功）"
                } else {
                    if (bridge.isDirectory("account/$name")) {
                        entries += "⏭ $name（本地同名是目录，跳过）"
                        return@executePlan
                    }
                    val partRel = "account/$name.part"
                    try {
                        client.get("$remoteRoot/$name", bridge.writeStream(partRel))
                        val expected = remote[name]?.size ?: 0L
                        val got = bridge.sizeOf(partRel)
                        if (expected > 0L && got != expected) {
                            error("$name 下载不完整（得 $got / 应 $expected）")
                        }
                        bridge.move(partRel, "account/$name")
                        runCatching {
                            val ts = parseBackupNameTime(name) ?: (remote[name]?.mtimeSec?.times(1000) ?: 0L)
                            if (ts > 0) bridge.touchLocal("account/$name", ts)
                        }
                    } finally {
                        bridge.deleteLocal(partRel)
}
                    entries += "↓ $name（成功）"
                }
                progress("transferring", entries.size, plan.transfer.size, name)
            },
            deleteOne = { name ->
                if (mode == SyncMode.MirrorToRemote) {
                    client.delete("$remoteRoot/$name")
                    entries += "✕ 云端 $name（已删）"
                } else {
                    bridge.deleteLocal("account/$name")
                    entries += "✕ 本地 $name（已删）"
                }
            }
        )
        entries += if (plan.skipped > 0) listOf("⏭ 同名跳过 ${plan.skipped} 个") else emptyList()
        return when {
            outcome.failedAt != null -> "失败于 ${outcome.failedAt}，已传 ${outcome.transferred} 个"
            mode == SyncMode.Download || mode == SyncMode.MirrorToLocal -> "下载 ${outcome.transferred} · 删除 ${outcome.deleted}"
            else -> "上传 ${outcome.transferred} · 删除 ${outcome.deleted}"
        }
    }

    private fun planOtherRange(client: WebDavClient): SyncPlan {
        val remoteBase = remoteDir.trim().trim('/')
        progress("planning", 0, 0, remoteBase)
        val remoteFlat = flattenRemote(client, remoteBase)
        val localFiles = bridge.list(BridgeClient.Scope.OTHER).associateBy { it.rel }
        return planScope(
            local = localFiles.keys,
            remote = remoteFlat,
            localMtimeSec = localFiles.mapValues { (_, r) -> r.mtimeMs / 1000 },
            rule = ConflictRule.MtimeNewerWins,
            mode = mode,
            localSizes = localFiles.mapValues { (_, r) -> r.size }
)
    }

    private fun runBidirectional(client: WebDavClient, entries: MutableList<String>): String {
        val remoteRoot = "${remoteDir.trim('/')}/account"
        val localFiles = bridge.list(BridgeClient.Scope.BACKUP).associateBy { it.rel }
        progress("planning", 0, 0, remoteRoot)
        val remote = runCatching { client.listDir(remoteRoot).filter { !it.isDir }.associateBy { it.name } }
            .getOrElse { if (it is WebDavException && it.status == 404) emptyMap() else throw it }
        val plan = planBackupBidirectional(
            local = localFiles.keys,
            remote = remote,
            keepLatest = keepLatest
        )
        if (!plan.hasWork) return "两侧已一致（本地窗口 = 云端最新 $keepLatest 份）"
        client.mkcol(remoteRoot)
        var done = 0
        val total = plan.upload.size + plan.download.size
        for (name in plan.upload) {
            checkNotLocked()
            progress("transferring", done, total, name)
            client.put("$remoteRoot/$name", bridge.readBytes("account/$name"))
            entries += "↑ $name（成功）"
            done++
        }

        for (name in plan.download) {
            checkNotLocked()
            progress("transferring", done, total, name)
            if (bridge.isDirectory("account/$name")) {
                entries += "⏭ $name（本地同名是目录，跳过）"
                done++
                continue
            }
            val partRel = "account/$name.part"
            try {
                client.get("$remoteRoot/$name", bridge.writeStream(partRel))
                val expected = remote[name]?.size ?: 0L
                val got = bridge.sizeOf(partRel)
                if (expected > 0L && got != expected) error("$name 下载不完整（得 $got / 应 $expected）")
                bridge.move(partRel, "account/$name")
                runCatching {
                    val ts = parseBackupNameTime(name) ?: (remote[name]?.mtimeSec?.times(1000) ?: 0L)
                    if (ts > 0) bridge.touchLocal("account/$name", ts)
                }
            } finally {
                        bridge.deleteLocal(partRel)
}
            entries += "↓ $name（成功）"
            done++
        }

        var trimmed = 0
        for (name in plan.trim) {
            checkNotLocked()
            bridge.deleteLocal("account/$name")
            entries += "✕ 本地 $name（超出保留份数，已裁剪；云端仍有）"
            trimmed++
        }
        return buildString {
            append("上传 ${plan.upload.size} · 下载 ${plan.download.size}")
            if (trimmed > 0) append(" · 裁剪本地 $trimmed")
        }
    }

    private fun runMirrorLocalNoConfirm(client: WebDavClient, entries: MutableList<String>): String {
        val remoteRoot = "${remoteDir.trim('/')}/account"
        val localFiles = bridge.list(BridgeClient.Scope.BACKUP).associateBy { it.rel }
        progress("planning", 0, 0, remoteRoot)
        val remote = runCatching { client.listDir(remoteRoot).filter { !it.isDir }.associateBy { it.name } }
            .getOrElse { if (it is WebDavException && it.status == 404) emptyMap() else throw it }
        val plan = planScope(
            local = localFiles.keys,
            remote = remote,
            localMtimeSec = localFiles.mapValues { (_, r) -> r.mtimeMs / 1000 },
            rule = ConflictRule.SameNameSkip,
mode = SyncMode.MirrorToLocal,
            localSizes = localFiles.mapValues { (_, r) -> r.size }
        )
        if (!plan.hasWork) return "本地已与云端一致"
        client.mkcol(remoteRoot)
        progress("transferring", 0, plan.transfer.size, "")
        var done = 0
        for (name in plan.transfer) {
            checkNotLocked()
            progress("transferring", done, plan.transfer.size, name)
            if (bridge.isDirectory("account/$name")) {
                entries += "⏭ $name（本地同名是目录，跳过）"
                done++
                continue
            }
            val partRel = "account/$name.part"
            try {
                client.get("$remoteRoot/$name", bridge.writeStream(partRel))
                val expected = remote[name]?.size ?: 0L
                val got = bridge.sizeOf(partRel)
                if (expected > 0L && got != expected) error("$name 下载不完整（得 $got / 应 $expected）")
                bridge.move(partRel, "account/$name")
                runCatching {
                    val ts = parseBackupNameTime(name) ?: (remote[name]?.mtimeSec?.times(1000) ?: 0L)
                    if (ts > 0) bridge.touchLocal("account/$name", ts)
                }
            } finally {
                bridge.deleteLocal(partRel)
            }
            entries += "↓ $name（成功）"
            done++
        }
        var deleted = 0
        for (name in plan.delete) {
            checkNotLocked()
            bridge.deleteLocal("account/$name")
            entries += "✕ 本地 $name（已删；云端仍有）"
            deleted++
        }
        return "下载 ${plan.transfer.size} · 删除本地 $deleted"
    }

    private fun runOtherRange(client: WebDavClient, entries: MutableList<String>): String {
        val remoteBase = remoteDir.trim().trim('/')
        val remoteFlat = flattenRemote(client, remoteBase)
        val localFiles = bridge.list(BridgeClient.Scope.OTHER).associateBy { it.rel }
        val plan = planScope(
            local = localFiles.keys,
            remote = remoteFlat,
            localMtimeSec = localFiles.mapValues { (_, r) -> r.mtimeMs / 1000 },
            rule = ConflictRule.MtimeNewerWins,
            mode = mode,
            localSizes = localFiles.mapValues { (_, r) -> r.size }
        )
        if (!plan.hasWork) return "两侧已一致，无需同步"
        progress("transferring", 0, plan.transfer.size, "")
        val outcome = executePlan(plan, mode,
            transferOne = { rel ->
                if (mode == SyncMode.Upload || mode == SyncMode.MirrorToRemote) {
                    localFiles[rel] ?: error("本地缺 $rel")
                    rel.split('/').dropLast(1).scan("") { acc, seg -> "$acc/$seg" }
                        .filter { it.isNotBlank() }
                        .forEach { client.mkcol("$remoteBase$it") }
                    client.put("$remoteBase/$rel", bridge.readBytes(rel))
                    entries += "↑ $rel（成功）"
                } else {
                    if (bridge.isDirectory(rel)) {
                        entries += "⏭ $rel（本地同名是目录，跳过）"
                        return@executePlan
                    }
                    client.get("$remoteBase/$rel", bridge.writeStream(rel))
                    runCatching {
                        val mtime = flattenRemoteOne(client, remoteBase, rel)?.mtimeSec ?: 0L
                        if (mtime > 0) bridge.touchLocal(rel, mtime * 1000)
                    }
                    entries += "↓ $rel（成功）"
                }
                progress("transferring", entries.size, plan.transfer.size, rel)
            },
            deleteOne = { rel ->
                if (mode == SyncMode.MirrorToRemote) {
                    client.delete("$remoteBase/$rel")
                    entries += "✕ 云端 $rel（已删）"
                } else {
                    bridge.deleteLocal(rel)
                    entries += "✕ 本地 $rel（已删）"
                }
            }
        )
        entries += if (plan.skipped > 0) listOf("⏭ 跳过 ${plan.skipped} 个") else emptyList()
        return when {
            outcome.failedAt != null -> "失败于 ${outcome.failedAt}"
            mode == SyncMode.Download || mode == SyncMode.MirrorToLocal -> "下载 ${outcome.transferred} 完成"
            else -> "上传 ${outcome.transferred} 完成"
        }
    }
}

internal fun flattenRemote(client: WebDavClient, base: String): Map<String, RemoteEntry> {
    val result = mutableMapOf<String, RemoteEntry>()
    fun walk(dir: String, prefix: String) {
        val items = try {
            client.listDir(dir)
        } catch (e: WebDavException) {
            if (e.status == 404) return
            throw e
        }
        for (entry in items) {
            val rel = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
            if (entry.isDir) walk("$dir/${entry.name}", rel) else result[rel] = entry
        }
    }
    walk(base, "")
    return result
}

internal fun flattenRemoteOne(client: WebDavClient, base: String, rel: String): RemoteEntry? =
    client.listDir("$base/${rel.substringBeforeLast('/')}").firstOrNull { it.name == rel.substringAfterLast('/') }
