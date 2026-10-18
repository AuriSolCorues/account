/**
 * 职责：备份目录的位置模型——结构固定为 `<位置>/Account/account` 两级（Account=同步根、
 *       account=备份目录，嵌套不可改，「位置」前缀可自选），提供位置前缀的规范化、
 *       完整子路径推导与展示串。纯函数，无 IO、无 Android 依赖，可直接单测。
 * 架构位置：AccountApp 持久化前与 BackupScreen 弹窗校验时都调它；真正的落盘/遍历在
 *           data/backup/BackupFiles.kt（直写 File 轨与 SAF DocumentFile 轨各自使用）。
 * Python 类比：≈ 一组纯校验/拼接函数——同输入同输出，不碰 IO；非法输入返回 None 由调用方兜底。
 */
package com.copy.account.data.backup

/** 同步根目录名：备份目录永远挂在它下面（结构固定 <位置>/Account/account）。 */
internal const val SYNC_ROOT_SEGMENT = "Account"

/** 备份目录名：同步根下的固定子目录，.acc 与 appsettings.json 都在这里。 */
internal const val BACKUP_SUBFOLDER = "account"

/** 默认备份子路径（位置为空 = 内部存储根直下）。历史值 backups/account 自 v2.0.0(22) 起废弃。 */
internal const val DEFAULT_BACKUP_FOLDER = "$SYNC_ROOT_SEGMENT/$BACKUP_SUBFOLDER"

/**
 * 规范化「位置」前缀（`<位置>/Account/account` 中可自选的那段）：空 = 内部存储根。
 * 去首尾空白、折叠重复斜杠、丢弃 `.` 段；前导 `/`（绝对路径）或含 `..` 上跳段 → null。
 * 单独的 `0` 等价留空——0 是内部存储根（/storage/emulated/0）的展示符号。
 */
internal fun normalizeBackupLocation(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.startsWith("/")) return null
    val segments = trimmed.split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) return null
    return segments.joinToString("/").let { if (it == "0") "" else it }
}

/** 由位置前缀推导完整备份子路径：`<位置>/Account/account`；空与 "0" 都表示内部存储根。 */
internal fun backupFolderPath(location: String): String {
    val prefix = location.trim().let { if (it == "0") "" else it }
    return if (prefix.isEmpty()) DEFAULT_BACKUP_FOLDER else "$prefix/$DEFAULT_BACKUP_FOLDER"
}

/** 备份位置的展示串：前缀为空显示为 0（内部存储根），结构两级恒可见。 */
internal fun backupLocationDisplay(location: String): String =
    "${location.trim().let { if (it == "0") "" else it }.ifBlank { "0" }}/$DEFAULT_BACKUP_FOLDER"

/**
 * 规范化备份子路径（通用兜底，仍供 SAF 轨授权目录下的相对子路径使用）：
 * 去首尾空白与尾部斜杠、折叠重复斜杠、丢弃 `.` 段。
 * 前导 `/`（绝对路径）或含 `..` 上跳段 → null（非法）；结果为空 → [DEFAULT_BACKUP_FOLDER]。
 *
 * 刻意拒绝绝对路径：`File(外部存储根, folder)` 依赖 folder 是相对路径。用户若打
 * `/storage/emulated/0/Download` 期望的是绝对路径，静默降级成「外部存储根下的同名子目录」
 * 只会让落盘位置变得莫名其妙，不如直接报错让他改。
 */
internal fun normalizeBackupFolder(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.startsWith("/")) return null
    val segments = trimmed.split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) return null
    val folder = segments.joinToString("/")
    return folder.ifEmpty { DEFAULT_BACKUP_FOLDER }
}
