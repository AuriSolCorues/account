/**
 * 职责：操作日志页（设置页「操作日志」二级页）——全应用审计流（账号增删/崩溃/导出）
 *       按时间倒序展示；条目带明细行。
 * 架构位置：AccountApp 的 AppPage.Logs 分支；数据经 runtime/OpLog（filesDir JSON Lines）。
 * Python 类比：≈ tail -r app-log.jsonl 的渲染——读文件、倒序、列出来。
 */
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

/** 事件类型的展示名。 */
private fun typeLabel(type: String): String = when (type) {
    LogEvents.ACCOUNT_ADD -> "新增账号"
    LogEvents.ACCOUNT_DELETE -> "删除账号"
    LogEvents.CRASH -> "软件崩溃"
    LogEvents.EXPORT -> "导出备份"
    else -> type
}

@Composable
internal fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var pendingClear by remember { mutableStateOf(false) }
    // 进页读一次（日志是审计记录，不搞实时刷新）；IO 在后台线程。
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
                                        // 展示层截断：明细最多 12 行（超长单行同样截住）；磁盘日志全量保留，
                                        // 全文要排查时走导出/清空前的日志文件，UI 不为异常长条目撑爆卡片
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

/** epoch millis → 「yyyy-MM-dd HH:mm:ss」。 */
internal fun formatLogTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
