package com.copy.account.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.copy.account.BuildConfig
import com.copy.account.ui.theme.AccountTheme

/** 删除确认框：红「删除」+「取消」，三处删除账号/分组共用。 */
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

/** 单输入框对话框（改名/新增分组共用）：内部持有文本，保存时经 onConfirm 回传。 */
@Composable
internal fun TextInputDialog(
    title: String,
    label: String,
    confirmText: String = "保存",
    initial: String = "",
    validate: (String) -> Boolean = { it.isNotBlank() },
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = {
            TextActionButton(confirmText, onClick = { onConfirm(text.trim()) }, enabled = validate(text))
        },
        dismissButton = { TextActionButton("取消", onDismiss) }
    )
}

@Preview(showBackground = true)
@Composable
private fun DeleteConfirmDialogPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
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
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        TextInputDialog(
            title = "新增分组",
            label = "分组名称",
            onConfirm = {},
            onDismiss = {}
        )
    }
}
