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
import com.copy.account.security.totpCode
import com.copy.account.data.model.Account
import com.copy.account.data.model.initialAccounts
import com.copy.account.ui.components.AppScreen
import com.copy.account.ui.components.EmptyState
import com.copy.account.ui.components.SensitiveValueRow
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.components.rememberClock
import com.copy.account.BuildConfig
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette

@Composable
internal fun AccountDetailScreen(account: Account?, clipboardClearSeconds: Int, onBack: () -> Unit, onEdit: () -> Unit, maskChar: Char = '•', onHotpAdvance: (String) -> Unit = {}) {
    val nowMillis = rememberClock()
    AppScreen(title = account?.name ?: "账号详情", onBack = onBack, actions = { TextActionButton("编辑", onEdit, textColor = LocalAccountThemePalette.current.topBarText) }) { padding ->
        if (account == null) {
            EmptyState("账号不存在", Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { Text("分组：${account.groups.joinToString().ifBlank { "默认" }}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp)) }
                item { Text("登录信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                item { SensitiveValueRow(account.usernameLabel?.ifBlank { "用户名" } ?: "用户名", account.username, masked = account.usernameHidden, clearAfterSeconds = clipboardClearSeconds, mask = maskChar) }
                item { SensitiveValueRow(account.passwordLabel?.ifBlank { "密码" } ?: "密码", account.password, masked = account.passwordHidden, clearAfterSeconds = clipboardClearSeconds, mask = maskChar) }
                item { Text("两步验证", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
                item {
                    if (account.hasTotp) {
                        val hotp = account.totpType.equals("HOTP", ignoreCase = true)
                        // HOTP 复制即 +1：复制到当前码后进位持久化，下一组码自动重绘。
                        SensitiveValueRow(
                            "动态密码",
                            totpCode(account, nowMillis),
                            sensitive = true,
                            clearAfterSeconds = clipboardClearSeconds,
                            afterCopy = if (hotp) { { onHotpAdvance(account.id) } } else null
                        )
                    } else Text("未配置", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
