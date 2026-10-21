/**
 * 职责：跨页通用件——时钟 rememberClock、顶栏配色、敏感值行、动态密码行、空态文案。
 *       （拖动排序手势/字形/动画卡片已归拢至 AnimatedReorderCard.kt。）
 * 架构位置：Home/Detail/Preview/GroupManage 等页面复用；复制动作走 ui/platform/Clipboard.kt。
 * Python 类比：rememberClock ≈ 起一个 asyncio 循环任务、每秒给「被观察的变量」赋新值——
 *           LaunchedEffect 里的 while(true)+delay 在 key 变化或组件销毁时自动取消重启。
 */
package com.copy.account.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.BuildConfig
import com.copy.account.data.model.Account
import com.copy.account.security.isHotp
import com.copy.account.security.totpCode
import com.copy.account.ui.platform.copyToClipboard
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette
import kotlinx.coroutines.delay

/** 时钟：按间隔刷新当前毫秒，驱动验证码等时间态内容。 */
@Composable
internal fun rememberClock(intervalMillis: Long = 1000): Long {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // 协程随 intervalMillis 启动：间隔变了旧循环自动取消重启；组件销毁（离开页面）也自动取消。
    LaunchedEffect(intervalMillis) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(intervalMillis)
        }
    }

    return nowMillis
}

@Composable
internal fun SensitiveValueRow(label: String, value: String, masked: Boolean = false, sensitive: Boolean = masked, clearAfterSeconds: Int = 30, mask: Char = '•', afterCopy: (() -> Unit)? = null) {
    val context = LocalContext.current
    var revealed by remember(value, masked) { mutableStateOf(!masked) }
    val displayed = if (masked && !revealed) mask.toString().repeat(8) else value
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(76.dp))
        Text(
            displayed,
            modifier = Modifier.weight(1f).clickable {
                if (masked && !revealed) {
                    revealed = true
                } else {
                    copyToClipboard(context, value, sensitive, clearAfterSeconds)
                    afterCopy?.invoke()
                }
            },
            fontWeight = if (masked && !revealed) FontWeight.Normal else FontWeight.Medium
        )
        TextActionButton("复制", onClick = {
            copyToClipboard(context, value, sensitive, clearAfterSeconds)
            afterCopy?.invoke()
        })
    }
}

/** 动态密码行（详情/速览共用）：HOTP 复制即 +1 进位。 */
@Composable
internal fun AccountTotpRow(account: Account, nowMillis: Long, clipboardClearSeconds: Int, onHotpAdvance: () -> Unit) {
    SensitiveValueRow(
        "动态密码",
        totpCode(account, nowMillis),
        sensitive = true,
        clearAfterSeconds = clipboardClearSeconds,
        afterCopy = if (account.isHotp) onHotpAdvance else null
    )
}

@Composable
internal fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Preview(showBackground = true)
@Composable
private fun SensitiveValueRowPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE == "system") {
        SensitiveValueRow("密码", "hunter2", masked = true, sensitive = true, clearAfterSeconds = 30)
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStatePreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE == "system") {
        EmptyState("暂无账号", Modifier.fillMaxWidth())
    }
}
