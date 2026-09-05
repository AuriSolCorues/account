package com.copy.account.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** 初始化固定的 backups/account 目录，返回最终目录。 */
internal fun initializeBackupDirectory(context: Context, treeUri: Uri): Result<Uri> = runCatching {
    val resolver = context.contentResolver
    resolver.takePersistableUriPermission(
        treeUri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    )
    val selected = DocumentFile.fromTreeUri(context, treeUri) ?: error("无法访问所选目录")
    require(selected.canRead() && selected.canWrite()) { "所选目录不可写，请选择可写目录" }
    // 若用户直接选中了 backups 文件夹，就在其中创建 account，避免 backups/backups 嵌套。
    val backups = if (selected.name.orEmpty().equals("backups", ignoreCase = true)) {
        selected
    } else {
        val existing = selected.findFile("backups")
        existing?.takeIf { it.isDirectory }
            ?: existing?.let { error("backups 名称已被文件占用") }
            ?: selected.createDirectory("backups")
            ?: error("无法创建 backups 目录")
    }
    val existingAccount = backups.findFile("account")
    val account = existingAccount?.takeIf { it.isDirectory }
        ?: existingAccount?.let { error("account 名称已被文件占用") }
        ?: backups.createDirectory("account")
        ?: error("无法创建 account 目录")
    require(account.isDirectory && account.canRead() && account.canWrite()) { "account 目录不可写" }
    account.uri
}

/** 按授权树 URI 找到固定的 backups/account 目录。 */
internal fun backupAccountDirectory(context: Context, treeUri: Uri): Result<DocumentFile> = runCatching {
    val selected = DocumentFile.fromTreeUri(context, treeUri) ?: error("无法访问授权目录")
    require(selected.canRead() && selected.canWrite()) { "授权目录不可写，请重新授权" }
    val backups = if (selected.name.orEmpty().equals("backups", ignoreCase = true)) selected else {
        val existing = selected.findFile("backups")
        existing?.takeIf { it.isDirectory }
            ?: existing?.let { error("backups 名称已被文件占用") }
            ?: selected.createDirectory("backups")
            ?: error("无法创建 backups 目录")
    }
    val existingAccount = backups.findFile("account")
    val account = existingAccount?.takeIf { it.isDirectory }
        ?: existingAccount?.let { error("account 名称已被文件占用") }
        ?: backups.createDirectory("account")
        ?: error("无法创建 account 目录")
    require(account.isDirectory && account.canRead() && account.canWrite()) { "backups/account 目录不可写" }
    account
}

internal data class BackupEntry(val file: DocumentFile, val size: Long, val modified: Long)

internal fun listBackupFiles(context: Context, treeUri: String?): Result<List<BackupEntry>> = runCatching {
    if (treeUri == null) return@runCatching emptyList()
    backupAccountDirectory(context, Uri.parse(treeUri)).getOrThrow()
        .listFiles()
        .filter { it.isFile && it.name?.endsWith(".acc", ignoreCase = true) == true }
        .sortedByDescending { it.lastModified() }
        .map { BackupEntry(it, it.length(), it.lastModified()) }
}

/** 读取授权目录中的备份文件。 */
internal fun readSelectedDocument(context: Context, uri: Uri): Result<ByteArray> = runCatching {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("文件提供方不支持读取")
}

internal fun writeBackupFile(context: Context, treeUri: Uri, bytes: ByteArray): String = runCatching {
    val directory = backupAccountDirectory(context, treeUri).getOrThrow()
    val base = "account-${java.text.SimpleDateFormat("yyyy-MM-dd-HHmmss", java.util.Locale.US).format(java.util.Date())}"
    var filename = "$base.acc"
    var index = 1
    while (directory.findFile(filename) != null) filename = "$base-${index++}.acc"
    val file = directory.createFile("application/octet-stream", filename) ?: error("无法创建备份文件")
    try {
        context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
            ?: error("无法写入备份文件")
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
    filename
}.getOrThrow()

internal fun deleteBackupFile(context: Context, uri: Uri): Result<Unit> = runCatching {
    require(DocumentFile.fromSingleUri(context, uri)?.delete() == true) { "删除备份失败" }
}

// ===== API>=30 直写路径（所有文件访问 + 固定目录），绕开被 OEM 锁死的 SAF 目录选择器；以下函数仅在 directBackup（API>=30）时调用 =====

/** API>=30 是否已授予「所有文件访问」。 */
internal fun hasStorageAccess(): Boolean = Environment.isExternalStorageManager()

/** 备份固定目录：内部存储/backups/account。 */
internal fun backupAccountDirectory(): File =
    File(Environment.getExternalStorageDirectory(), "backups/account")

/** 创建并校验固定的 backups/account 目录。 */
internal fun ensureBackupDirectory(): Result<File> = runCatching {
    val dir = backupAccountDirectory()
    if (!dir.isDirectory && !dir.mkdirs()) error("无法创建 backups/account 目录")
    require(dir.canRead() && dir.canWrite()) { "backups/account 目录不可写，请检查「所有文件访问」权限" }
    dir
}

internal data class FileBackupEntry(val file: File, val size: Long, val modified: Long)

internal fun listFileBackups(): Result<List<FileBackupEntry>> = runCatching {
    if (!hasStorageAccess()) return@runCatching emptyList()
    backupAccountDirectory().listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(".acc", ignoreCase = true) }
        .sortedByDescending { it.lastModified() }
        .map { FileBackupEntry(it, it.length(), it.lastModified()) }
}

internal fun readFileBackup(file: File): Result<ByteArray> = runCatching { file.readBytes() }

internal fun writeFileBackup(bytes: ByteArray): String = runCatching {
    val directory = ensureBackupDirectory().getOrThrow()
    val base = "account-${SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(java.util.Date())}"
    var filename = "$base.acc"
    var index = 1
    while (File(directory, filename).exists()) filename = "$base-${index++}.acc"
    val file = File(directory, filename)
    try {
        file.writeBytes(bytes)
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
    filename
}.getOrThrow()

internal fun deleteFileBackup(file: File): Result<Unit> = runCatching {
    require(file.delete()) { "删除备份失败" }
}
