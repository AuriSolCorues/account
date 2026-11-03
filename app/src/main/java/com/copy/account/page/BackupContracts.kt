package com.copy.account.page

internal interface BackupFileStore {

    fun refresh(onResult: (List<BackupRowUi>) -> Unit)

    fun read(row: BackupRowUi): ByteArray?

    fun delete(row: BackupRowUi, onDone: (Boolean) -> Unit)
}

internal interface BackupExporter {

    fun exportEncrypted(onDone: (Result<String>) -> Unit)

    fun exportPlaintext(onDone: (Result<String>) -> Unit)

    fun prepareExport(password: String): Result<Unit>
}

internal interface BackupDirectoryOps {

    val directBackup: Boolean

    val storageAccessGranted: Boolean

    val backupTreeUri: String?

    val backupLocation: String

    val backupFolder: String

    val backupNameTag: String

    fun changeBackupNameTag(raw: String): Boolean

    val directoryMessage: String

    val directoryExpanded: Boolean

    fun toggleDirectoryExpanded()

    fun changeBackupLocation(raw: String): Boolean

    fun changeBackupFolder(raw: String): Boolean

    fun chooseDirectory()

    fun requestStorageAccess()
}

internal data class BackupRowUi(
    val key: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val direct: Boolean
)

internal const val BACKUP_FEEDBACK_MILLIS = 5000L
