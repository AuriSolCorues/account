package com.copy.account.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.copy.account.runtime.LogEvents
import com.copy.account.runtime.LogEntry
import com.copy.account.runtime.clearLogs
import com.copy.account.runtime.readLogs
import com.copy.account.ui.components.AppScreen
import com.copy.account.ui.components.DeleteConfirmDialog
import com.copy.account.ui.components.EmptyState
import com.copy.account.ui.components.SurfaceCard
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.theme.LocalAccountThemePalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun typeLabel(type: String): String = when (type) {
    LogEvents.SYNC -> "扩展事件"
    LogEvents.ACCOUNT_ADD -> "新增账号"
    LogEvents.ACCOUNT_DELETE -> "删除账号"
    LogEvents.CRASH -> "软件崩溃"
    LogEvents.EXPORT -> "导出备份"
    LogEvents.EXTENSION -> "扩展"
    LogEvents.AUTOFILL -> "填充诊断"
    else -> type
}

@Composable
internal fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var pendingClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) { readLogs(context) }
        loading = false
    }

    AppScreen(
        title = "操作日志",
        onBack = onBack,
        actions = { TextActionButton("清空", onClick = { pendingClear = true }, textColor = LocalAccountThemePalette.current.topBarText) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
            when {
                loading -> EmptyState("正在加载…", Modifier.fillMaxWidth())
                entries.isEmpty() -> EmptyState("暂无日志", Modifier.fillMaxWidth())
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries) { entry ->
                        SurfaceCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row {
                                    Text(
                                        typeLabel(entry.type),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (entry.type == LogEvents.CRASH) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        formatLogTime(entry.timeMs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(entry.summary, style = MaterialTheme.typography.bodySmall)
                                if (entry.details.isNotEmpty()) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                                    Text(
                                        entry.details.take(50).joinToString("\n"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 12,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (entry.details.size > 50) {
                                        Text("…共 ${entry.details.size} 条", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (pendingClear) {
        DeleteConfirmDialog(
            title = "清空日志",
            message = "确定清空全部操作日志吗？此操作不可撤销。",
            onConfirm = {
                pendingClear = false
                entries = emptyList()
                CoroutineScope(Dispatchers.IO).launch { clearLogs(context) }
            },
            onDismiss = { pendingClear = false }
        )
    }
}

internal fun formatLogTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
