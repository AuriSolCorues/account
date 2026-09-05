/**
 * 职责：账号底部速览面板——不进详情页即可复制用户名/密码/验证码/自定义字段。
 * 架构位置：HomeScreen 点账号卡弹出；行组件复用 UiCommon 的 SensitiveValueRow/AccountTotpRow。
 * Python 类比：@Composable 无状态组件 ≈ 只吃 props 的模板函数；显示什么全由外部重传的数据决定。
 */
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
import com.copy.account.data.model.passwordRowLabel
import com.copy.account.data.model.usernameRowLabel
import com.copy.account.security.isHotp
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
    // HOTP 码不随时间变，长周期空转即可。
    val nowMillis = rememberClock(if (account.isHotp) 60_000L else 1000L)
    SheetTitleRow(account.name) {
        TextActionButton("详情", onDetail)
        TextActionButton("编辑", onEdit)
    }
    Spacer(Modifier.height(12.dp))
    SensitiveValueRow(account.usernameRowLabel, account.username, masked = account.usernameHidden, clearAfterSeconds = clipboardClearSeconds, mask = maskChar)
    SensitiveValueRow(account.passwordRowLabel, account.password, masked = account.passwordHidden, clearAfterSeconds = clipboardClearSeconds, mask = maskChar)
    if (account.hasTotp) AccountTotpRow(account, nowMillis, clipboardClearSeconds, onHotpAdvance)
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
