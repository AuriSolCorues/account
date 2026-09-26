/**
 * 职责：四个最小对话框封装——删除确认、单输入框（改名/新增分组）、单选列表（主题）、数值设置（自动锁定/剪贴板时长）。
 * 架构位置：各页面按需弹出；按钮视觉复用 Rows.kt 的 TextActionButton。
 * Python 类比：≈ tkinter.simpledialog.askstring / messagebox.askokcancel 的声明式写法——
 *           框架管理弹窗生命周期，回调上抛结果。
 */
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
import com.copy.account.BuildConfig
import com.copy.account.ui.theme.AccountTheme

/** 删除确认框：红「删除」+「取消」，删除账号/分组/备份共用。刻意不写调用处数量，加一处就得改注释。 */
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

/** 单选弹窗：一列选项按钮 + 取消；选项回调自带设值，选中即关闭。设置二级页的自动锁定/明暗/配色共用。 */
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

/**
 * 数值设置弹窗：一列预设按钮 + 分隔 + 自定义输入框，自动锁定（分钟）与剪贴板清除（秒）共用。
 * 单位不统一，由 unit 传入，各行保留自己的量纲；只有交互形态与越界校验一致。
 * presets 的回调与 ChoiceDialog 同义：未选中即执行，点完直接关闭。
 */
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
    // 空输入框只是「还没填」而非「填错」，不能一打开就红字报错；确定按钮仍由 valid 挡住。
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
        confirmButton = { TextActionButton("确定", onClick = { parsed?.let(onConfirm) }, enabled = valid) },
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

@Preview(showBackground = true)
@Composable
private fun ChoiceDialogPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
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
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
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
