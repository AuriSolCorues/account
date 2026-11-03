package com.copy.account.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.ui.theme.AccountTheme

@Composable
internal fun DeleteConfirmDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextActionButton("删除", onConfirm, textColor = MaterialTheme.colorScheme.error) },
        dismissButton = { TextActionButton("取消", onDismiss) }
    )
}

@Composable
internal fun ExportConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出加密备份？") },
        text = {
            Text(
                "将把当前全部账号与分组加密导出到备份文件夹。" +
                        "备份靠主密码加密，请牢记主密码——遗忘后备份将无法恢复。"
            )
        },
        confirmButton = { TextActionButton("确定导出", onConfirm, textColor = MaterialTheme.colorScheme.primary) },
        dismissButton = { TextActionButton("取消", onDismiss) }
    )
}

@Composable
internal fun TextInputDialog(
    title: String,
    label: String,
    confirmText: String = "保存",
    initial: String = "",
    validate: (String) -> Boolean = { it.isNotBlank() },
    supportingText: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true)
                if (supportingText != null) {
                    Text(supportingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextActionButton(confirmText, onClick = { onConfirm(text.trim()) }, enabled = validate(text))
        },
        dismissButton = { TextActionButton("取消", onDismiss) }
    )
}

@Composable
internal fun ChoiceDialog(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (label, action) ->
                    TextActionButton(label, onClick = { action(); onDismiss() }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextActionButton("取消", onClick = onDismiss) }
    )
}

@Composable
internal fun NumberSettingDialog(
    title: String,
    unit: String,
    minValue: Int,
    maxValue: Int,
    presets: List<Pair<String, () -> Unit>>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    val parsed = draft.trim().toIntOrNull()
    val valid = parsed != null && parsed in minValue..maxValue
    val showError = draft.isNotBlank() && !valid
    val hint = "自定义$unit（$minValue-$maxValue）"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).imePadding()
            ) {
                presets.forEach { (label, action) ->
                    TextActionButton(label, onClick = { action(); onDismiss() }, modifier = Modifier.fillMaxWidth())
                }
                HorizontalDivider()
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(unit) },
                    singleLine = true,
                    isError = showError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                if (showError) Text("请输入 $minValue-$maxValue 之间的整数", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextActionButton("确定", onClick = { parsed?.let(onConfirm); onDismiss() }, enabled = valid) },
        dismissButton = { TextActionButton("取消", onDismiss) }
    )
}

@Preview(showBackground = true)
@Composable
private fun DeleteConfirmDialogPreview() {
    AccountTheme(darkTheme = false) {
        DeleteConfirmDialog(
            title = "删除账号",
            message = "删除后无法恢复。",
            onConfirm = {},
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TextInputDialogPreview() {
    AccountTheme(darkTheme = false) {
        TextInputDialog(
            title = "新增分组",
            label = "分组名称",
            onConfirm = {},
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChoiceDialogPreview() {
    AccountTheme(darkTheme = false) {
        ChoiceDialog(
            title = "自动锁定",
            options = listOf("1 分钟" to {}, "5 分钟" to {}),
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NumberSettingDialogPreview() {
    AccountTheme(darkTheme = false) {
        NumberSettingDialog(
            title = "自动锁定",
            unit = "分钟",
            minValue = 1,
            maxValue = 1440,
            presets = listOf("关闭" to {}, "5 分钟" to {}),
            onConfirm = {},
            onDismiss = {}
        )
    }
}
