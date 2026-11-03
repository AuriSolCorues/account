package com.copy.account.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import com.copy.account.data.backup.DEFAULT_BACKUP_FOLDER
import com.copy.account.data.backup.backupFolderPath
import com.copy.account.data.backup.hasStorageAccess
import com.copy.account.data.backup.initializeBackupDirectory
import com.copy.account.data.backup.normalizeBackupFolder
import com.copy.account.data.backup.normalizeBackupLocation
import com.copy.account.data.backup.normalizeBackupNameTag
import com.copy.account.data.config.BACKUP_DIRECTORY_EXPANDED_SETTING
import com.copy.account.data.config.BACKUP_FOLDER_SETTING
import com.copy.account.data.config.BACKUP_LOCATION_SETTING
import com.copy.account.data.config.BACKUP_NAME_TAG_SETTING
import com.copy.account.data.config.BACKUP_TREE_URI_SETTING
import com.copy.account.data.config.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class BackupLocationState(
    private val context: Context,
    private val scope: CoroutineScope,
    val directBackup: Boolean
) {
    var backupTreeUri by mutableStateOf<String?>(null)
        private set

    var backupDirectoryMessage by mutableStateOf("")
        private set

    var backupLocation by mutableStateOf("")
        private set

    var backupFolder by mutableStateOf(DEFAULT_BACKUP_FOLDER)
        private set

    var backupNameTag by mutableStateOf("")
        private set

    var storageAccessGranted by mutableStateOf(directBackup && hasStorageAccess())
        private set

    var directoryExpanded by mutableStateOf(false)
        private set

    fun clearMessage() {
        backupDirectoryMessage = ""
    }

    suspend fun loadInitial() {
        val values = context.settingsDataStore.data.first()
        backupTreeUri = values[BACKUP_TREE_URI_SETTING]
        backupLocation = normalizeBackupLocation(values[BACKUP_LOCATION_SETTING].orEmpty()) ?: ""
        backupFolder = backupFolderPath(backupLocation)
        backupNameTag = normalizeBackupNameTag(values[BACKUP_NAME_TAG_SETTING].orEmpty()) ?: ""
        directoryExpanded = values[BACKUP_DIRECTORY_EXPANDED_SETTING] ?: false
    }

    fun onDirectoryChosen(uri: Uri?) {
        if (uri == null) {
            backupDirectoryMessage = "未选择目录"
        } else {
            val result = initializeBackupDirectory(context, uri, backupFolder)
            if (result.isSuccess) {
                backupTreeUri = uri.toString()
                persistTreeUri(backupTreeUri)
                backupDirectoryMessage = "已授权，备份保存于 $backupFolder"
            } else {
                backupDirectoryMessage = result.exceptionOrNull()?.message ?: "目录不可写，请重新选择"
            }
        }
    }

    fun changeBackupLocation(raw: String): Boolean {
        val normalized = normalizeBackupLocation(raw) ?: return false
        backupLocation = normalized
        backupFolder = backupFolderPath(normalized)
        persistLocation(normalized)
        return true
    }

    fun changeBackupFolder(raw: String): Boolean {
        val normalized = normalizeBackupFolder(raw) ?: return false
        backupFolder = normalized
        persistFolder(normalized)
        return true
    }

    fun changeBackupNameTag(raw: String): Boolean {
        val normalized = normalizeBackupNameTag(raw) ?: return false
        backupNameTag = normalized
        persistNameTag(normalized)
        return true
    }

    fun refreshStorageAccess() {
        if (directBackup) storageAccessGranted = hasStorageAccess()
    }

    fun toggleDirectoryExpanded() {
        directoryExpanded = !directoryExpanded
        scope.launch {
            context.settingsDataStore.edit { it[BACKUP_DIRECTORY_EXPANDED_SETTING] = directoryExpanded }
        }
    }

    fun requestStorageAccess(): Boolean {
        if (!directBackup) return false
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun persistTreeUri(uri: String?) {
        scope.launch {
            context.settingsDataStore.edit {
                if (uri == null) it.remove(BACKUP_TREE_URI_SETTING) else it[BACKUP_TREE_URI_SETTING] = uri
            }
        }
    }

    private fun persistLocation(location: String) {
        scope.launch {
            context.settingsDataStore.edit { it[BACKUP_LOCATION_SETTING] = location }
        }
    }

    private fun persistFolder(folder: String) {
        scope.launch {
            context.settingsDataStore.edit { it[BACKUP_FOLDER_SETTING] = folder }
        }
    }

    private fun persistNameTag(tag: String) {
        scope.launch {
            context.settingsDataStore.edit { it[BACKUP_NAME_TAG_SETTING] = tag }
        }
    }
}

@Composable
internal fun rememberBackupLocationState(): BackupLocationState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { BackupLocationState(context, scope, Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) }
    LaunchedEffect(Unit) { state.loadInitial() }
    return state
}
