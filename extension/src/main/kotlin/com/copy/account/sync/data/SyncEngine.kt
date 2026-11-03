package com.copy.account.sync.data

import java.io.File

internal enum class SyncMode {
    Upload,
    Download,
    MirrorToRemote,
    MirrorToLocal
}

internal enum class ConflictRule {
    SameNameSkip,
    MtimeNewerWins
}

internal data class SyncPlan(
    val transfer: List<String>,
    val delete: List<String>,
    val skipped: Int
) {
    val hasWork: Boolean get() = transfer.isNotEmpty() || delete.isNotEmpty()
}

internal fun planScope(
    local: Set<String>,
    remote: Map<String, RemoteEntry>,
    localMtimeSec: Map<String, Long>,
    rule: ConflictRule,
    mode: SyncMode,
    localSizes: Map<String, Long> = emptyMap()
): SyncPlan {
    val localNames = local
    val remoteNames = remote.keys
    val onlyLocal = (localNames - remoteNames).sorted()
    val onlyRemote = (remoteNames - localNames).sorted()
    val both = (localNames intersect remoteNames).sorted()

    return when (mode) {
        SyncMode.Upload, SyncMode.Download -> {
            val transfer = when (mode) {
                SyncMode.Upload -> onlyLocal + when (rule) {
                    ConflictRule.SameNameSkip -> emptyList()
                    ConflictRule.MtimeNewerWins -> both.filter { isLocalNewer(it, localMtimeSec, remote) }
                }
                SyncMode.Download -> onlyRemote + when (rule) {
                    ConflictRule.SameNameSkip -> emptyList()
                    ConflictRule.MtimeNewerWins -> both.filter { !isLocalNewer(it, localMtimeSec, remote) && !isTie(it, localMtimeSec, remote) }
                }
                else -> emptyList()
            }
            SyncPlan(transfer = transfer, delete = emptyList(), skipped = when (rule) {
                ConflictRule.SameNameSkip -> both.size
                ConflictRule.MtimeNewerWins -> both.size - transfer.count { it in both }
            })
        }
        SyncMode.MirrorToRemote, SyncMode.MirrorToLocal -> {
            val same = both.filter { name ->
                val localSec = localMtimeSec[name]
                val remoteSec = remote[name]?.mtimeSec
                val localSize = localSizes[name]
                val remoteSize = remote[name]?.size
                localSec != null && localSec == remoteSec &&
                        localSize != null && localSize == remoteSize
            }
            val bothDiff = both - same.toSet()
            val transfer = when (mode) {
                SyncMode.MirrorToRemote -> onlyLocal + bothDiff
                SyncMode.MirrorToLocal -> onlyRemote + bothDiff
                else -> emptyList()
            }
            val delete = when (mode) {
                SyncMode.MirrorToRemote -> onlyRemote
                SyncMode.MirrorToLocal -> onlyLocal
else -> emptyList()
            }
            SyncPlan(transfer = transfer, delete = delete, skipped = same.size)
        }
    }
}

private fun isLocalNewer(name: String, localMtimeSec: Map<String, Long>, remote: Map<String, RemoteEntry>): Boolean {
    val localSec = localMtimeSec[name] ?: 0L
    val remoteSec = remote[name]?.mtimeSec ?: 0L
    return localSec > remoteSec
}

private fun isTie(name: String, localMtimeSec: Map<String, Long>, remote: Map<String, RemoteEntry>): Boolean {
    val localSec = localMtimeSec[name] ?: 0L
    val remoteSec = remote[name]?.mtimeSec ?: 0L
    return localSec == remoteSec
}

internal data class BackupBidirectionalPlan(
    val upload: List<String>,
    val download: List<String>,
    val trim: List<String>
) {
    val hasWork: Boolean get() = upload.isNotEmpty() || download.isNotEmpty() || trim.isNotEmpty()
}

internal fun planBackupBidirectional(
    local: Set<String>,
    remote: Map<String, RemoteEntry>,
    keepLatest: Int
): BackupBidirectionalPlan {
    val window = remote.values
        .sortedWith(compareByDescending<RemoteEntry> { it.mtimeSec }.thenBy { it.name })
        .take(keepLatest.coerceAtLeast(1))
        .map { it.name }
        .toSet()
    val upload = (local - remote.keys).sorted()
    val download = (window - local).sorted()
    val trim = (local - window - upload.toSet()).sorted()
    return BackupBidirectionalPlan(upload = upload, download = download, trim = trim)
}

internal data class SyncOutcome(val transferred: Int, val deleted: Int, val failedAt: String?)

internal fun executePlan(
    plan: SyncPlan,
    mode: SyncMode,
    transferOne: (String) -> Unit,
    deleteOne: (String) -> Unit
): SyncOutcome {
    var transferred = 0
    for (path in plan.transfer) {
        try {
            transferOne(path)
            transferred++
        } catch (e: Exception) {
            return SyncOutcome(transferred = transferred, deleted = 0, failedAt = path)
        }
    }
    var deleted = 0
    for (path in plan.delete) {
        try {
            deleteOne(path)
            deleted++
        } catch (e: Exception) {
            return SyncOutcome(transferred = transferred, deleted = deleted, failedAt = path)
        }
    }
    return SyncOutcome(transferred = transferred, deleted = deleted, failedAt = null)
}

internal fun collectLocalFiles(root: File): Map<String, File> {
    if (!root.isDirectory) return emptyMap()
    return buildMap {
        root.walkTopDown()
            .onEnter { dir -> dir == root || dir.name != "account" }
.filter { it.isFile }
            .forEach { file ->
                val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
                if (!rel.substringAfterLast('/').startsWith("account-plain-")) {
                    put(rel, file)
                }
            }
    }
}
