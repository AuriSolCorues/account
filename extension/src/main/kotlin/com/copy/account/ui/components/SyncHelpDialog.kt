package com.copy.account.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

fun SyncHelpText(): String =
    "【密码文件同步】\n" +
            "⇄ 同步：新备份上传云端；下载云端最新 N 份；本地只保留最新 N 份（卡片内「本地保留份数」可设，默认 5）。\n" +
            "⇩ 云端覆盖本地：本地完全对齐云端，多余备份直接删除。\n" +
            "云端永久保留全部备份——任何操作都不会删除云端备份，本地旧备份删了随时可从云端重新下载。\n" +
            "\n【其他文件同步】\n" +
            "↑ 上传 / ↓ 下载：只复制对方没有的或更新的文件，永不删除。\n" +
            "⇧ 覆盖云端：云端对齐本地，云端多余文件删除（删除前列清单确认）。\n" +
            "⇩ 覆盖本地：本地对齐云端，本地多余文件删除（删除前列清单确认）。\n" +
            "\n密码文件同名跳过（时间戳命名同名必同内容）；其他文件同名时新版本覆盖旧版本。\n" +
            "明文导出文件永不上云。"

@Composable
fun SyncHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("同步说明") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(SyncHelpText(), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextActionButton("知道了", onDismiss) }
    )
}
