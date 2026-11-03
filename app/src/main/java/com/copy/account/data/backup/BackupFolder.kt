package com.copy.account.data.backup

internal const val SYNC_ROOT_SEGMENT = "Account"

internal const val BACKUP_SUBFOLDER = "account"

internal const val DEFAULT_BACKUP_FOLDER = "$SYNC_ROOT_SEGMENT/$BACKUP_SUBFOLDER"

internal fun normalizeBackupLocation(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.startsWith("/")) return null
    val segments = trimmed.split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) return null
    return segments.joinToString("/").let { if (it == "0") "" else it }
}

internal fun backupFolderPath(location: String): String {
    val prefix = location.trim().let { if (it == "0") "" else it }
    return if (prefix.isEmpty()) DEFAULT_BACKUP_FOLDER else "$prefix/$DEFAULT_BACKUP_FOLDER"
}

internal fun backupLocationDisplay(location: String): String =
    "${location.trim().let { if (it == "0") "" else it }.ifBlank { "0" }}/$DEFAULT_BACKUP_FOLDER"

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

internal fun normalizeBackupNameTag(raw: String): String? {
    val cleaned = raw.trim()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("[/\\\\:*?\"<>|]"), "")
        .replace(Regex("-+"), "-")
        .trim('-')
    return when {
        cleaned.isEmpty() && raw.isBlank() -> ""
        cleaned.isEmpty() -> null
        else -> cleaned.take(24)
    }
}
