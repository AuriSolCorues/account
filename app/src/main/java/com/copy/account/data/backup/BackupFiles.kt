/**
 * 职责：备份文件的双轨读写——API>=30 直写可自定义的内部存储子目录（默认 内部存储/backups/account）
 *       里的 .acc 文件（普通 File IO，需「所有文件访问」权限）；API<30 走 SAF（用户授权目录树，
 *       经 contentResolver 以 content:// URI 操作 DocumentFile）。两轨共用 uniqueBackupName 命名，
 *       产出同一种 .acc 文件。
 * 架构位置：AccountApp 决定走哪轨（directBackup）并组装授权流程，BackupScreen 只管展示与回调；
 *           子路径本身由 data/backup/BackupFolder.kt 规范化（纯函数，可单测）；.acc 的加密内容由
 *           security/AccCodec 生成，本文件只做存取、不做加解密。
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

/** 逐段遍历子目录，缺失的段顺手创建；末级不存在则报错。SAF 轨的建目录唯一入口。 */
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

/**
 * 只读查找子目录下的某个文件，**不创建任何目录**——给「读外挂配置文件」用。
 * 目录或文件不存在一律返回 null（调用方据此回落到 DataStore 真值）。
 */
internal fun findFileInFolder(root: DocumentFile, folder: String, name: String): DocumentFile? {
    var current = root
    for (segment in folder.split('/').filter { it.isNotBlank() }) {
        current = current.findFile(segment)?.takeIf { it.isDirectory } ?: return null
    }
    return current.findFile(name)?.takeIf { it.isFile }
}

/** 初始化备份目录（逐段创建），返回最终目录。 */
internal fun initializeBackupDirectory(context: Context, treeUri: Uri, folder: String): Result<Uri> = runCatching {
    val resolver = context.contentResolver
    // 把「仅本次会话」的目录授权升级为跨重启持久授权；不调用则重启后对同一 URI 失去读写权。
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

/** 按授权树 URI 找到备份目录。 */
internal fun backupAccountDirectory(context: Context, treeUri: Uri, folder: String): Result<DocumentFile> = runCatching {
    val selected = DocumentFile.fromTreeUri(context, treeUri) ?: error("无法访问授权目录")
    require(selected.canRead() && selected.canWrite()) { "授权目录不可写，请重新授权" }
    val directory = resolveDirectory(selected, folder)
    require(directory.isDirectory && directory.canRead() && directory.canWrite()) { "$folder 目录不可写" }
    directory
}

// SAF 轨的列表行模型；FileBackupEntry 是直写轨的对应物，字段一一对应。
internal data class BackupEntry(val file: DocumentFile, val size: Long, val modified: Long)

internal fun listBackupFiles(context: Context, treeUri: String?, folder: String): Result<List<BackupEntry>> = runCatching {
    if (treeUri == null) return@runCatching emptyList()
    backupAccountDirectory(context, Uri.parse(treeUri), folder).getOrThrow()
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

/**
 * 备份命名器：给它一个「该名字是否已被占用」的判定，回一个不冲突的名字。
 * 写盘函数负责把目录查找能力喂进去，因此调用方不必自己先解析目录（省掉一次重复的目录解析）。
 */
internal typealias BackupNameFactory = ((String) -> Boolean) -> String

internal fun writeBackupFile(
    context: Context,
    treeUri: Uri,
    bytes: ByteArray,
    folder: String,
    nameFactory: BackupNameFactory = ::uniqueBackupName,
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

/** 备份文件名：account-时间戳；重名追加 -1/-2 序号。SAF 与直写两轨共用，命名保持一致。 */
internal fun uniqueBackupName(exists: (String) -> Boolean): String {
    val base = "account-${SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())}"
    var filename = "$base.acc"
    var index = 1
    while (exists(filename)) filename = "$base-${index++}.acc"
    return filename
}

/**
 * 明文导出文件名：account-plain-时间戳.json；去重规则与 uniqueBackupName 完全一致。
 * 刻意不抽象成共用函数——在用的加密备份命名不值得动，抄 4 行比改它风险低。
 * 前缀带 plain 是给用户看的：这文件一眼就该被认出来、尽快删掉。
 */
internal fun uniquePlainExportName(exists: (String) -> Boolean): String {
    val base = "account-plain-${SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())}"
    var filename = "$base.json"
    var index = 1
    while (exists(filename)) filename = "$base-${index++}.json"
    return filename
}

/** 明文导出 JSON 里 exportedAt 字段的取值；与文件名同源但格式适合人读（冒号在 JSON 里无碍）。 */
internal fun plainExportTimestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

// ===== API>=30 直写路径（所有文件访问 + 可自定义子目录），绕开被 OEM 锁死的 SAF 目录选择器；以下函数仅在 directBackup（API>=30）时调用 =====

/** API>=30 是否已授予「所有文件访问」。 */
internal fun hasStorageAccess(): Boolean = Environment.isExternalStorageManager()

/** 备份目录：内部存储根下的指定子路径（由 normalizeBackupFolder 保证是安全相对路径）。 */
internal fun backupAccountDirectory(folder: String): File =
    File(Environment.getExternalStorageDirectory(), folder)

/** 创建并校验备份目录。 */
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
    nameFactory: BackupNameFactory = ::uniqueBackupName
): String = runCatching {
    val directory = ensureBackupDirectory(folder).getOrThrow()
    // 同 writeBackupFile：直写轨是普通 File IO，没有 MIME 概念，故只接受命名器。
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
