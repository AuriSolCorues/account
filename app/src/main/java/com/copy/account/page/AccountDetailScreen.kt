package com.copy.account.page

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.copy.account.ui.components.AppListScreen
import com.copy.account.ui.components.AccountTotpRow
import com.copy.account.ui.components.AppScreen
import com.copy.account.ui.components.EmptyState
import com.copy.account.ui.components.SensitiveValueRow
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.components.rememberClock
import com.copy.account.BuildConfig
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette

@Composable
internal fun AccountDetailScreen(
    account: Account?,
    clipboardClearSeconds: Int,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    maskChar: Char = '•',
    onHotpAdvance: (String) -> Unit = {},
    hasPasskey: Boolean = false
) {
    val nowMillis = rememberClock(if (account?.isHotp == true) 60_000L else 1000L)
    val title = account?.name ?: "账号详情"
    val topBarActions: @Composable RowScope.() -> Unit = { TextActionButton("编辑", onEdit, textColor = LocalAccountThemePalette.current.topBarText) }
    if (account == null) {
        AppScreen(title = title, onBack = onBack, actions = topBarActions) { padding ->
            EmptyState("账号不存在", Modifier.fillMaxSize().padding(padding))
        }
    } else {
        AppListScreen(title, onBack, actions = topBarActions, spacing = 4.dp) {
            item { Text("分组：${account.groups.joinToString().ifBlank { "默认" }}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 12.dp)) }
            item { Text("登录信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            item { SensitiveValueRow(account.usernameRowLabel, account.username, masked = account.usernameHidden, clearAfterSeconds = clipboardClearSeconds, mask = maskChar) }
            if (hasPasskey) {
                item {
                    Text("🔑 本机通行密钥已绑定", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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

@Preview(showBackground = true)
@Composable
private fun AccountDetailScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE == "system") {
        AccountDetailScreen(account = initialAccounts.last(), clipboardClearSeconds = 30, onBack = {}, onEdit = {})
    }
}
