package com.copy.account.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.security.isHotp
import com.copy.account.data.model.Account
import com.copy.account.data.model.initialAccounts
import com.copy.account.data.model.passwordRowLabel
import com.copy.account.data.model.usernameRowLabel
import com.copy.account.ui.components.AccountTotpRow
import com.copy.account.ui.components.AppScreen
import com.copy.account.ui.components.EmptyState
import com.copy.account.ui.components.SensitiveValueRow
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.components.rememberClock
import com.copy.account.BuildConfig
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette

/**
 * 账号详情展示页面。只读的 不可改变
 *
 * @param account 当前查看的账号数据，若为 null 则显示空状态。
 * @param clipboardClearSeconds 剪贴板内容自动清空的时间（秒）。
 * @param onBack 返回上一页的回调。
 * @param onEdit 触发编辑账号的回调。
 * @param maskChar 敏感信息隐藏时使用的掩码字符，默认为 '•'。
 * @param onHotpAdvance HOTP 验证码复制后触发计数器递增的回调。
 */
@Composable
internal fun AccountDetailScreen(account: Account?, clipboardClearSeconds: Int, onBack: () -> Unit, onEdit: () -> Unit, maskChar: Char = '•', onHotpAdvance: (String) -> Unit = {}) {
    // 动态调整时钟刷新频率：HOTP 码不随时间变化，设为 60 秒长周期空转以节省性能；TOTP 需每秒刷新。
    val nowMillis = rememberClock(if (account?.isHotp == true) 60_000L else 1000L)
    // 外层标准应用屏幕布局，包含顶部导航栏（标题、返回、编辑按钮）
    AppScreen(title = account?.name ?: "账号详情", onBack = onBack, actions = { TextActionButton("编辑", onEdit, textColor = LocalAccountThemePalette.current.topBarText) }) { padding ->
        if (account == null) {
            // 账号数据为空时显示占位提示
            EmptyState("账号不存在", Modifier.fillMaxSize().padding(padding))
        } else {
            // 账号数据存在时，使用列表滚动展示各项详细信息 在垂直列表的相邻子项之间，固定保留 4.dp 的间距。这是一个撑满可用空间、左右各留白20dp，且内部相邻元素间保持4dp间距的垂直滚动列表。
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { Text("分组：${account.groups.joinToString().ifBlank { "默认" }}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp)) }
                item { Text("登录信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                item { SensitiveValueRow(account.usernameRowLabel, account.username, masked = account.usernameHidden, clearAfterSeconds = clipboardClearSeconds, mask = maskChar) }
                item { SensitiveValueRow(account.passwordRowLabel, account.password, masked = account.passwordHidden, clearAfterSeconds = clipboardClearSeconds, mask = maskChar) }
                item { Text("两步验证", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
                item {
                    if (account.hasTotp) AccountTotpRow(account, nowMillis, clipboardClearSeconds) { onHotpAdvance(account.id) }
                    else Text("未配置", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(account.customFields, key = { it.id }) { field -> SensitiveValueRow(field.label, field.value, masked = field.hidden, sensitive = field.hidden, clearAfterSeconds = clipboardClearSeconds, mask = maskChar) }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AccountDetailScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        AccountDetailScreen(account = initialAccounts.last(), clipboardClearSeconds = 30, onBack = {}, onEdit = {})
    }
}
