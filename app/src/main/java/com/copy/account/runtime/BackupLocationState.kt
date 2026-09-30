/**
 * 职责：备份目录与存储授权状态容器——SAF 目录授权（API<30）与「所有文件访问」直写（API>=30）
 *       两轨的路径/授权/提示状态集中于此：DataStore 冷启动读入、SAF 选目录结果落账、
 *       子路径变更与持久化、ON_RESUME 授权刷新、跳系统授权页。
 *       SAF launcher 的注册仍留在 AccountApp（rememberLauncherForActivityResult 必须在组合里），
 *       回调经 onDirectoryChosen 落账到这里。
 * 架构位置：重构 P1 从 AccountApp 抽出的第一块状态——rememberBackupLocationState() 在
 *           AccountApp 组合内创建本容器，BackupScreen 的相关 props 与回调改由容器供给；
 *           DataStore 键值仍在 data/config/Preferences.kt，路径规范化/目录初始化仍在
 *           data/backup/（纯函数与 Result IO），本容器只持状态、写回与跳转分派。
 * Python 类比：≈ 一个 mixin/组件类——把原 App 类里散落的备份目录相关实例属性与方法
 *           搬进独立对象；mutableStateOf ≈ 该对象的受观察属性，赋值即触发依赖它的渲染。
 */
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
import com.copy.account.data.backup.hasStorageAccess
import com.copy.account.data.backup.initializeBackupDirectory
import com.copy.account.data.backup.normalizeBackupFolder
import com.copy.account.data.config.BACKUP_FOLDER_SETTING
import com.copy.account.data.config.BACKUP_TREE_URI_SETTING
import com.copy.account.data.config.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 备份目录与存储授权状态容器。
 *
 * [directBackup] 为 true（API>=30）时走「所有文件访问」直写轨，[storageAccessGranted]
 * 反映系统授权并在 ON_RESUME 刷新；false（API<30）时走 SAF 轨，[backupTreeUri] 是
 * 用户授权的目录树 URI。两轨共用 [backupFolder] 子路径。
 */
internal class BackupLocationState(
    private val context: Context,
    private val scope: CoroutineScope,
    val directBackup: Boolean
) {
    /** SAF 轨：用户授权的目录树 URI（content:// 字符串）；直写轨不使用。 */
    var backupTreeUri by mutableStateOf<String?>(null)
        private set

    /** 目录选择/授权结果的用户提示；重选目录前经 [clearMessage] 清空。 */
    var backupDirectoryMessage by mutableStateOf("")
        private set

    /** 备份子路径（相对内部存储根）；用户可在备份页手填，非法值由 normalizeBackupFolder 挡掉。 */
    var backupFolder by mutableStateOf(DEFAULT_BACKUP_FOLDER)
        private set

    /** 直写轨是否已授予「所有文件访问」；SAF 轨恒为 false。 */
    var storageAccessGranted by mutableStateOf(directBackup && hasStorageAccess())
        private set

    /** 对外清空目录提示：重选目录前调用，等价原 requestBackupDirectory 里的置空。 */
    fun clearMessage() {
        backupDirectoryMessage = ""
    }

    /**
     * 冷启动从 DataStore 读一次备份相关真值（不持续订阅，之后靠回调增量更新）。
     * 存的值可能来自旧版本或被手改，兜底再规范化一次：DataStore 是外部输入，视为不可信。
     */
    suspend fun loadInitial() {
        val values = context.settingsDataStore.data.first()
        backupTreeUri = values[BACKUP_TREE_URI_SETTING]
        backupFolder = normalizeBackupFolder(values[BACKUP_FOLDER_SETTING].orEmpty()) ?: DEFAULT_BACKUP_FOLDER
    }

    /**
     * SAF launcher 回调落账：null → 提示未选择；否则初始化目录（取持久授权 + 逐段建目录），
     * 成功才写状态并持久化 URI，失败提示异常信息。
     */
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

    /** 改备份子路径：normalize 返回 null（绝对路径或含 ..）视为非法，返回 false 供弹窗提示。 */
    fun changeBackupFolder(raw: String): Boolean {
        val normalized = normalizeBackupFolder(raw) ?: return false
        backupFolder = normalized
        persistFolder(normalized)
        return true
    }

    /** 从系统设置授予/撤销「所有文件访问」后返回时刷新（ON_RESUME 调用）。 */
    fun refreshStorageAccess() {
        if (directBackup) storageAccessGranted = hasStorageAccess()
    }

    /**
     * 跳系统「所有文件访问」授权页；授予后返回，由 ON_RESUME 刷新 storageAccessGranted。
     * 跳转动作直接执行（runCatching 吞掉无 Activity 接收的场景），返回值仅测试用。
     */
    fun requestStorageAccess(): Boolean {
        if (!directBackup) return false
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** SAF 轨：授权树 URI 写回 DataStore（null = 移除键）。 */
    private fun persistTreeUri(uri: String?) {
        scope.launch {
            context.settingsDataStore.edit {
                if (uri == null) it.remove(BACKUP_TREE_URI_SETTING) else it[BACKUP_TREE_URI_SETTING] = uri
            }
        }
    }

    /** 备份子路径写回 DataStore。 */
    private fun persistFolder(folder: String) {
        scope.launch {
            context.settingsDataStore.edit { it[BACKUP_FOLDER_SETTING] = folder }
        }
    }
}

/**
 * 组合内创建并记住 [BackupLocationState]：容器只建一次，loadInitial 也只在首帧跑一次。
 * SAF launcher 的注册（rememberLauncherForActivityResult）仍留在 AccountApp，
 * 回调里调 state.onDirectoryChosen(uri) 即完成落账。
 */
@Composable
internal fun rememberBackupLocationState(): BackupLocationState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { BackupLocationState(context, scope, Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) }
    LaunchedEffect(Unit) { state.loadInitial() }
    return state
}
