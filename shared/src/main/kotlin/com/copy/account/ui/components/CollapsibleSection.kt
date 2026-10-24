/**
 * 职责：可折叠分组控件——标题行（标题 + 展开指示符）+ 展开时才组合的内容区。
 *       纯展示组件：展开状态与回调都由调用方持有传入，自身不存状态。
 * 架构位置：ui/components 通用控件层；备份页「备份设置」分组首个使用。
 * Python 类比：≈ 一个带 visible 开关的模板块——expanded=False 时 content 完全不渲染
 *           （不进组合树，而非仅隐藏），收起即省去内容区的组合与测量开销。
 */
package com.copy.account.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 可折叠分组：点击标题行切换展开/收起。
 * @param expanded 展开状态（调用方持有，如 remember { mutableStateOf(true) }）
 * @param onToggle 点击标题行的回调（调用方翻转 expanded）
 * @param content 展开时才进入组合的内容；收起时完全不组合
 * 指示符用文本符号 ▾/▸：项目零 Material Icons 依赖，不为一个箭头引入图标库。
 */
@Composable
internal fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                if (expanded) "▾" else "▸",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (expanded) content()
    }
}
