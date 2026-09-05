package com.copy.account.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.BuildConfig
import com.copy.account.ui.theme.AccountTheme

/** 设置页的分组标题。 */
@Composable
internal fun SettingsHeader(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

/** 设置页的标题 + 当前值 + 可点击行。 */
@Composable
internal fun SettingsRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    SurfaceCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f))
            Text(value, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 设置页的开关行。 */
@Composable
internal fun SettingsSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SurfaceCard(onClick = { onCheckedChange(!checked) }, modifier = Modifier.fillMaxWidth()) {
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
    TextActionButton(text, onClick, modifier, textColor = MaterialTheme.colorScheme.error)
}

/** 扁平内容卡：surface 底色、无阴影，与主题背景区分；可选点击（可点击行/开关行基座）。 */
@Composable
internal fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    if (onClick == null) {
        Card(colors = colors, elevation = elevation, modifier = modifier, content = content)
    } else {
        Card(onClick = onClick, colors = colors, elevation = elevation, modifier = modifier, content = content)
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsHeaderPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        SettingsHeader("设置行")
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsRowPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        SettingsRow("自动锁定", "5 分钟")
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsSwitchRowPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        SettingsSwitchRow("允许截图", true, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun SwitchRowPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        SwitchRow("两步验证", false, {}, subtitle = "已配置 · 自动显示在动态密码分组")
    }
}

@Preview(showBackground = true)
@Composable
private fun DangerButtonPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        DangerButton("删除账号", onClick = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun SurfaceCardPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        SurfaceCard {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("surface 卡片", modifier = Modifier.weight(1f))
                Text("值", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
