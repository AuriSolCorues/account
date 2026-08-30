package com.copy.account.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** 设置页的分组标题。 */
@Composable
internal fun SettingsHeader(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

/** 设置页的标题 + 当前值 + 可点击行。 */
@Composable
internal fun SettingsRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    Card(onClick = onClick ?: {}, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f))
            Text(value, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 设置页的开关行。 */
@Composable
internal fun SettingsSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(onClick = { onCheckedChange(!checked) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/** 通用开关行：左侧标题（可带副标题）+ 右侧开关。编辑页两步验证等表单复用。 */
@Composable
internal fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 红字危险按钮（删除等），统一错误色。 */
@Composable
internal fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) { Text(text, color = MaterialTheme.colorScheme.error) }
}

/** 扁平内容卡：surface 底色、无阴影，与主题背景区分。 */
@Composable
internal fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier,
        content = content
    )
}

/** 密码输入框：右侧「显示/隐藏」按钮切明文/掩码，掩码字符可配置。 */
@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    mask: Char = '•',
    modifier: Modifier = Modifier
) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(mask = mask),
        trailingIcon = {
            TextButton(onClick = { show = !show }) {
                Text(if (show) "隐藏" else "显示", style = MaterialTheme.typography.labelMedium)
            }
        },
        modifier = modifier
    )
}

/** 账号编辑页的自定义字段行（可隐藏为掩码，可删除）。 */
@Composable
internal fun AccountFieldItem(label: String, value: String, hidden: Boolean, onValueChange: (String) -> Unit, onDelete: (() -> Unit)? = null, mask: Char = '•') {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = true, visualTransformation = if (hidden) PasswordVisualTransformation(mask = mask) else VisualTransformation.None, modifier = Modifier.weight(1f))
        if (onDelete != null) TextButton(onClick = onDelete) { Text("删除") }
    }
}
