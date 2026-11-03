package com.copy.account.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun resolveDirectory(root: DocumentFile, folder: String): DocumentFile {
    var current = root
    for (segment in folder.split('/').filter { it.isNotBlank() }) {
        val existing = current.findFile(segment)
        current = existing?.takeIf { it.isDirectory }
            ?: existing?.let { error("$segment 名称已被文件占用") }
            ?: current.createDirectory(segment)
            ?: error("无法创建 $segment 目录")
    }
    return current
}

internal fun findFileInFolder(root: DocumentFile, folder: String, name: String): DocumentFile? {
    var current = root
    for (segment in folder.split('/').filter { it.isNotBlank() }) {
        current = current.findFile(segment)?.takeIf { it.isDirectory } ?: return null
    }
    return current.findFile(name)?.takeIf { it.isFile }
}

internal fun initializeBackupDirectory(context: Context, treeUri: Uri, folder: String): Result<Uri> = runCatching {
    val resolver = context.contentResolver
    resolver.takePersistableUriPermission(
        treeUri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    )
    val selected = DocumentFile.fromTreeUri(context, treeUri) ?: error("无法访问所选目录")
    require(selected.canRead() && selected.canWrite()) { "所选目录不可写，请选择可写目录" }
    val directory = resolveDirectory(selected, folder)
    require(directory.isDirectory && directory.canRead() && directory.canWrite()) { "$folder 目录不可写" }
    directory.uri
}

internal fun backupAccountDirectory(context: Context, treeUri: Uri, folder: String): Result<DocumentFile> = runCatching {
    val selected = DocumentFile.fromTreeUri(context, treeUri) ?: error("无法访问授权目录")
    require(selected.canRead() && selected.canWrite()) { "授权目录不可写，请重新授权" }
    val directory = resolveDirectory(selected, folder)
    require(directory.isDirectory && directory.canRead() && directory.canWrite()) { "$folder 目录不可写" }
    directory
}

internal data class BackupEntry(val file: DocumentFile, val size: Long, val modified: Long)

internal fun listBackupFiles(context: Context, treeUri: String?, folder: String): Result<List<BackupEntry>> = runCatching {
    if (treeUri == null) return@runCatching emptyList()
    backupAccountDirectory(context, Uri.parse(treeUri), folder).getOrThrow()
        .listFiles()
        .filter { it.isFile && it.name?.endsWith(".acc", ignoreCase = true) == true }
        .sortedByDescending { it.lastModified() }
        .map { BackupEntry(it, it.length(), it.lastModified()) }
}

internal fun readSelectedDocument(context: Context, uri: Uri): Result<ByteArray> = runCatching {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("文件提供方不支持读取")
}

internal typealias BackupNameFactory = ((String) -> Boolean) -> String

internal fun writeBackupFile(
    context: Context,
    treeUri: Uri,
    bytes: ByteArray,
    folder: String,
    nameFactory: BackupNameFactory = { uniqueBackupName("", it) },
    mimeType: String = "application/octet-stream"
): String = runCatching {
    val directory = backupAccountDirectory(context, treeUri, folder).getOrThrow()
    val name = nameFactory { directory.findFile(it) != null }
    val file = directory.createFile(mimeType, name) ?: error("无法创建备份文件")
    try {
        context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
            ?: error("无法写入备份文件")
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
    name
}.getOrThrow()

internal fun deleteBackupFile(context: Context, uri: Uri): Result<Unit> = runCatching {
    require(DocumentFile.fromSingleUri(context, uri)?.delete() == true) { "删除备份失败" }
}

internal fun uniqueBackupName(tag: String, exists: (String) -> Boolean): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val base = if (tag.isBlank()) stamp else "$stamp-$tag"
    var filename = "$base.acc"
    var index = 1
    while (exists(filename)) filename = "$base-${index++}.acc"
    return filename
}

internal fun uniquePlainExportName(exists: (String) -> Boolean): String {
    val base = "account-plain-${SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())}"
    var filename = "$base.json"
    var index = 1
    while (exists(filename)) filename = "$base-${index++}.json"
    return filename
}

internal fun hasStorageAccess(): Boolean = Environment.isExternalStorageManager()

internal fun backupAccountDirectory(folder: String): File =
    File(Environment.getExternalStorageDirectory(), folder)

internal fun ensureBackupDirectory(folder: String): Result<File> = runCatching {
    val dir = backupAccountDirectory(folder)
    if (!dir.isDirectory && !dir.mkdirs()) error("无法创建 $folder 目录")
    require(dir.canRead() && dir.canWrite()) { "$folder 目录不可写，请检查「所有文件访问」权限" }
    dir
}

internal data class FileBackupEntry(val file: File, val size: Long, val modified: Long)

internal fun listFileBackups(folder: String): Result<List<FileBackupEntry>> = runCatching {
    if (!hasStorageAccess()) return@runCatching emptyList()
    backupAccountDirectory(folder).listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(".acc", ignoreCase = true) }
        .sortedByDescending { it.lastModified() }
        .map { FileBackupEntry(it, it.length(), it.lastModified()) }
}

internal fun readFileBackup(file: File): Result<ByteArray> = runCatching { file.readBytes() }

internal fun writeFileBackup(
    bytes: ByteArray,
    folder: String,
    nameFactory: BackupNameFactory = { uniqueBackupName("", it) }
): String = runCatching {
    val directory = ensureBackupDirectory(folder).getOrThrow()
    val name = nameFactory { File(directory, it).exists() }
    val file = File(directory, name)
    try {
        file.writeBytes(bytes)
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
    name
}.getOrThrow()

internal fun deleteFileBackup(file: File): Result<Unit> = runCatching {
    require(file.delete()) { "删除备份失败" }
}
