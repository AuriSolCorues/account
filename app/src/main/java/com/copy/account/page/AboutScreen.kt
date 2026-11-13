package com.copy.account.page

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.copy.account.BuildConfig
import com.copy.account.ui.components.AppListScreen
import com.copy.account.ui.components.SettingsRow
import com.copy.account.ui.theme.AccountTheme

private const val PROJECT_URL = "https://github.com/AuriSolCorues/account"

@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    AppListScreen("关于", onBack) {
        item { SettingsRow("版本", "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})") }
        item {
            SettingsRow("开源地址", "打开", onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))) }
            })
        }
        item {
            SettingsRow(
                "鸣谢",
                "感谢我的同学lion的图标设计 这里采用昵称 替换真名",
                valueAlign = TextAlign.Start
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE == "system") {
        AboutScreen(onBack = {})
    }
}
