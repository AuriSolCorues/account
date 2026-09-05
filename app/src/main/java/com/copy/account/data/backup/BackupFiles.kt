/**
 * 职责：备份文件的双轨读写——API>=30 直写固定路径 内部存储下 backups/account 目录里的 .acc 文件
 *       （普通 File IO，需「所有文件访问」权限）；API<30 走 SAF（用户授权目录树，经 contentResolver 以
 *       content:// URI 操作 DocumentFile）。两轨共用 uniqueBackupName 命名，产出同一种 .acc 文件。
 * 架构位置：AccountApp 决定走哪轨（directBackup）并组装授权流程，BackupScreen 只管展示与回调；
 *           .acc 的加密内容由 security/AccCodec 生成，本文件只做存取、不做加解密。
 * Python 类比：SAF 无对应物——用户授权的是「句柄」（URI + 持久权限）而非路径字符串，
 *           只能经 contentResolver 读写，类似 OS 只发 fd 不给文件名。File 轨 ≈ 普通 open()。
 */
package com.copy.account.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Result<T>：Kotlin 标准库的「值或异常」容器——runCatching { ... } 把异常捕进 Result
// （≈ 把 try/except 折进返回值），调用方用 isSuccess/getOrNull/getOrThrow 决定何时才真正抛。
// 本文件所有可能失败的 IO 都返回 Result，逐层上抛由页面决定怎么提示。

/** 初始化固定的 backups/account 目录，返回最终目录。 */
internal fun initializeBackupDirectory(context: Context, treeUri: Uri): Result<Uri> = runCatching {
    val resolver = context.contentResolver
    // 把「仅本次会话」的目录授权升级为跨重启持久授权；不调用则重启后对同一 URI 失去读写权。
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

// SAF 轨的列表行模型；FileBackupEntry 是直写轨的对应物，字段一一对应。
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
    val filename = uniqueBackupName { directory.findFile(it) != null }
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

/** 备份文件名：account-时间戳；重名追加 -1/-2 序号。SAF 与直写两轨共用，命名保持一致。 */
internal fun uniqueBackupName(exists: (String) -> Boolean): String {
    val base = "account-${SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())}"
    var filename = "$base.acc"
    var index = 1
    while (exists(filename)) filename = "$base-${index++}.acc"
    return filename
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
    val filename = uniqueBackupName { File(directory, it).exists() }
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
