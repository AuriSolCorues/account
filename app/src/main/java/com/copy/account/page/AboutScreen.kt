package com.copy.account.page

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.copy.account.BuildConfig
import com.copy.account.R
import com.copy.account.ui.components.AppListScreen
import com.copy.account.ui.components.SettingsRow
import com.copy.account.ui.theme.AccountTheme

private const val PROJECT_URL = "https://github.com/AuriSolCorues/account"

@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    AppListScreen(stringResource(R.string.about_title), onBack) {
        item { SettingsRow(stringResource(R.string.about_version), "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})") }
        item {
            SettingsRow(stringResource(R.string.about_open_source), stringResource(R.string.about_open_action), onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))) }
            })
        }
        item {
            SettingsRow(
                stringResource(R.string.about_credits),
                stringResource(R.string.about_credits_text),
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
