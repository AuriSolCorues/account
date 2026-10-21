/**
 * 职责：设置页与表单的行组件族——标题行、标题+当前值行、开关行、危险按钮、扁平内容卡。
 *       全部无状态：数据与回调经参数进出，组件自己不存业务状态。
 * 架构位置：SettingsScreen 大量使用，编辑页复用 SwitchRow/SurfaceCard。
 * Python 类比：≈ 一组纯函数模板（Jinja2 macro）：同参数进、同 UI 出，无副作用。
 */
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.copy.account.ui.theme.AccountTheme

/** 设置页的分组标题。 */
@Composable
internal fun SettingsHeader(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

/**
 * 设置行：左侧标题、右侧取值；onClick 非空时整卡可点。
 * 布局要点：标题**不带 weight**，因为 Row 先测无 weight 的子节点——若标题带 weight(1f)，
 * 右侧那个无 weight 的长值会先把整行宽度吃光，标题被压成竖排一列（37 字符的 URL 曾真的如此）。
 * 值用 weight(1f) 拿剩余宽度并配 textAlign=End，短值照样靠右、长值在此换行，两头都不破。
 * valueAlign=Start 供长文案行用（如关于页鸣谢）：值换行后左对齐更易读，右对齐的换行尾巴参差难看。
 */
@Composable
internal fun SettingsRow(
    title: String,
    value: String,
    valueAlign: TextAlign = TextAlign.End,
    onClick: (() -> Unit)? = null
) {
    SurfaceCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title)
            Text(
                value,
                color = MaterialTheme.colorScheme.primary,
                textAlign = valueAlign,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 设置页的开关行：只有开关本体响应点击，整卡不可点（防误触——生物识别解锁的关闭是
 * 立即生效的高危操作，误点行内空白不该触发）。enabled=false 时仅开关置灰不可点
 * （如设备不支持该能力），卡片底色保持不变。
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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
    // onClick 可空双态：null 走纯展示 Card，非 null 走可点击 Card（自带涟漪与无障碍语义）。
    if (onClick == null) {
        Card(colors = colors, elevation = elevation, modifier = modifier, content = content)
    } else {
        Card(onClick = onClick, colors = colors, elevation = elevation, modifier = modifier, content = content)
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsHeaderPreview() {
    AccountTheme(darkTheme = false) {
        SettingsHeader("设置行")
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsRowPreview() {
    AccountTheme(darkTheme = false) {
        SettingsRow("自动锁定", "5 分钟")
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsSwitchRowPreview() {
    AccountTheme(darkTheme = false) {
        SettingsSwitchRow("允许截图", true, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun SwitchRowPreview() {
    AccountTheme(darkTheme = false) {
        SwitchRow("两步验证", false, {}, subtitle = "已配置 · 自动显示在动态密码分组")
    }
}

@Preview(showBackground = true)
@Composable
private fun DangerButtonPreview() {
    AccountTheme(darkTheme = false) {
        DangerButton("删除账号", onClick = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun SurfaceCardPreview() {
    AccountTheme(darkTheme = false) {
        SurfaceCard {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("surface 卡片", modifier = Modifier.weight(1f))
                Text("值", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
