/**
 * 职责：备份目录的子路径规范化——把用户手填的字符串收敛成一个安全的相对路径，
 *       并提供默认目录常量。纯函数，无 IO、无 Android 依赖，可直接单测。
 * 架构位置：AccountApp 持久化前与 BackupScreen 弹窗校验时都调它；真正的落盘/遍历在
 *           data/backup/BackupFiles.kt（直写 File 轨与 SAF DocumentFile 轨各自使用）。
 * Python 类比：≈ 一个纯校验函数——同输入同输出，不碰 IO；非法输入返回 None 由调用方兜底。
 */
package com.copy.account.data.backup

/** 默认备份子目录。与历史硬编码路径一致，改动它会让老用户的备份在列表里「消失」。 */
internal const val DEFAULT_BACKUP_FOLDER = "backups/account"

/**
 * 规范化备份子路径：去首尾空白与尾部斜杠、折叠重复斜杠、丢弃 `.` 段。
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
