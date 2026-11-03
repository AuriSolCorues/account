package com.copy.account.sync

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.copy.account.platform.BridgeContract
import com.copy.account.sync.config.WebDavConfigState
import com.copy.account.sync.navigation.SyncPage
import com.copy.account.sync.page.SyncConnectionScreen
import com.copy.account.sync.page.SyncHomeScreen
import com.copy.account.sync.runtime.PlatformMirror
import com.copy.account.sync.runtime.SyncSession
import com.copy.account.ui.components.TextActionButton

@Composable
internal fun SyncApp(mirror: PlatformMirror, onExit: () -> Unit) {
    val context = LocalContext.current
    val pageScope = rememberCoroutineScope()

    val configState = remember { WebDavConfigState(context, pageScope) }
    LaunchedEffect(Unit) { configState.loadInitial() }
    val session = remember { SyncSession(context, pageScope, configState) }
    var page by remember { mutableStateOf(SyncPage.Home) }

    BackHandler(enabled = page != SyncPage.Home) { page = SyncPage.Home }

    if (mirror.locked) {
        LaunchedEffect(Unit) { onExit() }
        SyncLockedOverlay()
        return
    }

    when (page) {
        SyncPage.Home -> SyncHomeScreen(session, onOpenPage = { page = it }, onExit = onExit)
        SyncPage.Connection -> SyncConnectionScreen(session, onOpenPage = { page = it })
    }

    session.pendingPlan?.let { plan ->
        val mode = session.pendingMode
        AlertDialog(
            onDismissRequest = { session.dismissPlan() },
            title = { Text("${mode?.label ?: ""}确认") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("传输 ${plan.transfer.size} · 跳过 ${plan.skipped} · 删除 ${plan.delete.size}")
                    if (plan.transfer.isNotEmpty()) {
                        Text(
                            "传输清单：\n" + plan.transfer.take(8).joinToString("\n") +
                                    if (plan.transfer.size > 8) "\n…共 ${plan.transfer.size} 项" else ""
                        )
                    }
                    if (plan.delete.isNotEmpty()) {
                        val deleteTarget = if (mode == com.copy.account.sync.runtime.SyncModeName.MirrorToRemote) "云端" else "本地"
                        Text(
                            "将删除$deleteTarget ${plan.delete.size} 个：\n" + plan.delete.take(8).joinToString("\n") +
                                    if (plan.delete.size > 8) "\n…共 ${plan.delete.size} 项" else "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (plan.deleteRatioOverHalf) {
                        Text(
                            "⚠ 待删数超过目标侧文件总数一半，属高危操作，请逐项核对删除清单",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    val isMirror = mode == com.copy.account.sync.runtime.SyncModeName.MirrorToRemote ||
                            mode == com.copy.account.sync.runtime.SyncModeName.MirrorToLocal
                    if (!isMirror && plan.delete.isEmpty()) {
                        Text(
                            "此操作只新增/更新，不删除任何文件",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = { TextActionButton("确定执行", onClick = { session.confirmPlan() }) },
            dismissButton = { TextActionButton("取消", onClick = { session.dismissPlan() }) }
        )
    }

    session.showResult?.let { s ->
        AlertDialog(
            onDismissRequest = { session.dismissResult() },
            title = { Text("同步完成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.summary)
                    if (s.entries.isNotEmpty()) {
                        Text(
                            s.entries.take(8).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (s.entries.size > 8) {
                            Text(
                                "…共 ${s.entries.size} 条明细",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = { TextActionButton("好", onClick = { session.dismissResult() }) }
        )
    }
}

@Composable
private fun SyncLockedOverlay() {
    val context = LocalContext.current
    val launchMain = remember {
        runCatching {
            Intent().setClassName(
                BridgeContract.AUTHORITY.substringBefore('.'), "com.copy.account.MainActivity"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrNull()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (launchMain != null) {
                Button(onClick = { runCatching { context.startActivity(launchMain) } }) {
                    Text("解锁 → 打开主应用")
                }
            } else {
                Text(
                    "主应用未安装",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
