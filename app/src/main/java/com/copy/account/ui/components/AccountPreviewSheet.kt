package com.copy.account.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.BuildConfig
import com.copy.account.data.model.Account
import com.copy.account.data.model.initialAccounts
import com.copy.account.security.totpCode
import com.copy.account.ui.theme.AccountTheme

/** 账号底部速览面板：快速复制字段与 2FA，点击空白或下滑关闭。 */
@Composable
internal fun AccountPreviewSheet(
    account: Account,
    clipboardClearSeconds: Int,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDetail: () -> Unit,
    maskChar: Char = '•',
    onHotpAdvance: () -> Unit = {}
) {
    AppBottomSheet(onDismiss = onDismiss) {
        AccountPreviewContent(account, clipboardClearSeconds, maskChar, onEdit, onDetail, onHotpAdvance)
    }
}

/** 速览内容（不含弹层容器），运行时与 IDE 预览共用。 */
@Composable
private fun AccountPreviewContent(
    account: Account,
    clipboardClearSeconds: Int,
    maskChar: Char,
    onEdit: () -> Unit,
    onDetail: () -> Unit,
    onHotpAdvance: () -> Unit = {}
) {
    val nowMillis = rememberClock()
    SheetTitleRow(account.name) {
        TextActionButton("详情", onDetail)
        TextActionButton("编辑", onEdit)
    }
    Spacer(Modifier.height(12.dp))
    SensitiveValueRow(account.usernameLabel?.ifBlank { "用户名" } ?: "用户名", account.username, masked = account.usernameHidden, clearAfterSeconds = clipboardClearSeconds, mask = maskChar)
    SensitiveValueRow(account.passwordLabel?.ifBlank { "密码" } ?: "密码", account.password, masked = account.passwordHidden, clearAfterSeconds = clipboardClearSeconds, mask = maskChar)
    if (account.hasTotp) {
        // HOTP 复制即 +1：复制到当前码后进位，下组码自动重绘。
        val hotp = account.totpType.equals("HOTP", ignoreCase = true)
        SensitiveValueRow("动态密码", totpCode(account, nowMillis), sensitive = true, clearAfterSeconds = clipboardClearSeconds, afterCopy = if (hotp) onHotpAdvance else null)
    }
    account.customFields.forEach { field -> SensitiveValueRow(field.label, field.value, masked = field.hidden, sensitive = field.hidden, clearAfterSeconds = clipboardClearSeconds, mask = maskChar) }
    Spacer(Modifier.height(16.dp))
}

@Preview(name = "账号速览面板", widthDp = 411, heightDp = 700, showBackground = true)
@Composable
private fun AccountPreviewSheetPagePreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        SheetPagePreview {
            AccountPreviewContent(initialAccounts[1], clipboardClearSeconds = 30, maskChar = '•', onEdit = {}, onDetail = {})
        }
    }
}
