package com.copy.account.sync.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.copy.account.sync.navigation.SyncPage
import com.copy.account.sync.runtime.ACTION_BIDIRECTIONAL
import com.copy.account.sync.runtime.ACTION_MIRROR_LOCAL_NC
import com.copy.account.sync.runtime.SyncModeName
import com.copy.account.sync.runtime.SyncSession
import com.copy.account.sync.runtime.actionLabel
import com.copy.account.ui.components.AppListScreen
import com.copy.account.ui.components.CollapsibleSection
import com.copy.account.ui.components.SyncHelpDialog
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.components.TopBarTextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SyncHomeScreen(session: SyncSession, onOpenPage: (SyncPage) -> Unit, onExit: () -> Unit) {
    val configState = session.configState
    var showHelp by remember { mutableStateOf(false) }
    AppListScreen(
        "云同步",
        onBack = onExit,
        actions = {
            TopBarTextButton("?", onClick = { showHelp = true })
        }
    ) {
        item { TextActionButton("⚙ 网盘连接配置", onClick = { onOpenPage(SyncPage.Connection) }) }
        item { BackupSyncCard(session) }
        item { OtherFilesSyncCard(session) }
    }
    if (showHelp) SyncHelpDialog(onDismiss = { showHelp = false })
}

@Composable
private fun BackupSyncCard(session: SyncSession) {
    val configState = session.configState
    var expanded by remember { mutableStateOf(true) }
    var showKeepDialog by remember { mutableStateOf(false) }
    CollapsibleSection(title = "密码文件同步", expanded = expanded, onToggle = { expanded = !expanded }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val enabled = configState.configured && !session.busy
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { session.runSync(ACTION_BIDIRECTIONAL, rangeBackup = true) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text("⇄ 同步", maxLines = 1)
                }
                TextButton(onClick = { session.runSync(ACTION_MIRROR_LOCAL_NC, rangeBackup = true) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text("⇩ 云端覆盖本地", maxLines = 1)
                }
            }
            CardProgress(session, rangeBackup = true)
            TextActionButton(
                "本地保留份数：${configState.keepLatest}",
                onClick = { showKeepDialog = true }
            )
            if (!configState.configured) {
                Text("未配置：先在上方填写网盘连接", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            CardLastResult(configState.lastSyncMillis, configState.lastResult)
        }
    }
    if (showKeepDialog) KeepLatestDialog(
        current = configState.keepLatest,
        onPick = { configState.updateKeepLatest(it); showKeepDialog = false },
        onDismiss = { showKeepDialog = false }
    )
}

@Composable
private fun OtherFilesSyncCard(session: SyncSession) {
    val configState = session.configState
    var expanded by remember { mutableStateOf(true) }
    CollapsibleSection(title = "其他文件同步", expanded = expanded, onToggle = { expanded = !expanded }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val enabled = configState.configured && !session.busy
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                SyncModeName.entries.forEach { mode ->
                    TextButton(onClick = { session.runSync(mode.name, rangeBackup = false) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text(mode.label, maxLines = 1)
                    }
                }
            }
            CardProgress(session, rangeBackup = false)
            if (!configState.configured) {
                Text("未配置：先在上方填写网盘连接", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            CardLastResult(configState.lastSyncMillis, configState.lastResult)
        }
    }
}

@Composable
private fun CardProgress(session: SyncSession, rangeBackup: Boolean) {
    if (!session.busy || session.busyKind == "test") return
    if (session.progressRangeBackup != rangeBackup) return
    val p = session.progress ?: return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LinearProgressIndicator(
            progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            session.progressText(p, session.progressActionName),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun CardLastResult(lastSyncMillis: Long, lastResult: String) {
    if (lastSyncMillis > 0L) {
        Text(
            "上次同步：${formatSyncTime(lastSyncMillis)} · $lastResult",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun KeepLatestDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(current.toString()) }
    val parsed = draft.trim().toIntOrNull()
    val valid = parsed != null && parsed in 1..50
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本地保留份数") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "⇄ 同步后本地只保留云端最新 N 份备份；云端始终保留全部。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf(3, 5, 10, 20).forEach { n ->
                    TextActionButton(if (n == current) "● $n 份" else "$n 份", onClick = { onPick(n) }, modifier = Modifier.fillMaxWidth())
                }
                androidx.compose.material3.HorizontalDivider()
                androidx.compose.material3.OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("自定义（1-50）") },
                    singleLine = true,
                    isError = draft.isNotBlank() && !valid
                )
            }
        },
        confirmButton = {
            TextActionButton("确定", onClick = { if (valid) onPick(parsed!!) }, enabled = valid)
        },
        dismissButton = { TextActionButton("取消", onClick = onDismiss) }
    )
}

internal fun formatSyncTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
