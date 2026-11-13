package com.copy.account.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.data.config.ACCENT_THEME_SETTING
import com.copy.account.data.config.CUSTOM_THEME_JSON_SETTING
import com.copy.account.data.config.THEME_MODE_SETTING
import com.copy.account.data.config.settingsDataStore
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.resolveDarkTheme
import kotlinx.coroutines.flow.first

@Composable
fun OverlayThemeHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val stored by produceState(Triple(BuildDefaults.themeMode, BuildDefaults.accent, BuildDefaults.customJson)) {
        value = runCatching {
            val v = context.settingsDataStore.data.first()
            Triple(
                v[THEME_MODE_SETTING] ?: BuildDefaults.themeMode,
                v[ACCENT_THEME_SETTING] ?: BuildDefaults.accent,
                v[CUSTOM_THEME_JSON_SETTING] ?: BuildDefaults.customJson
            )
        }.getOrDefault(BuildDefaults.triple)
    }
    val (themeMode, accentTheme, customThemeJson) = stored
    AccountTheme(
        darkTheme = resolveDarkTheme(themeMode, androidx.compose.foundation.isSystemInDarkTheme()),
        accentTheme = accentTheme,
        customThemeJson = customThemeJson,
        content = content
    )
}

private object BuildDefaults {
    val themeMode: String get() = com.copy.account.BuildConfig.DEFAULT_THEME_MODE
    const val accent: String = "green"
    const val customJson: String = ""
    val triple: Triple<String, String, String>
        get() = Triple(themeMode, accent, customJson)
}

@Composable
internal fun OverlaySheetItems(
    title: String,
    headline: String,
    description: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    extraContent: @Composable ColumnScope.() -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SheetTitleRow(title)
        Text(headline, style = MaterialTheme.typography.titleMedium)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        extraContent()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextActionButton("取消", onClick = onCancel, modifier = Modifier.weight(1f))
            PrimaryButton(confirmText, onClick = onConfirm, modifier = Modifier.weight(2f))
        }
    }
}

@Composable
internal fun AutofillPasswordSheetItems(onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var pwd by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    OverlaySheetItems(
        title = "验证主密码",
        headline = "验证后向目标应用填充所选账号",
        description = "本设备未配置指纹，输入主密码完成验证",
        confirmText = "验证并填充",
        onConfirm = {
            if (!submitting) {
                submitting = true
                onConfirm(pwd)
            }
        },
        onCancel = onCancel,
        extraContent = {
            PasswordField("主密码", pwd, { pwd = it }, showPasswordToggle = true)
        }
    )
}

private val PreviewThemeMatrix: List<Pair<String, Triple<Boolean, String, String>>> = listOf(
    "绿·明" to Triple(false, "green", ""),
    "绿·暗" to Triple(true, "green", ""),
    "蓝·明" to Triple(false, "blue", ""),
    "蓝·暗" to Triple(true, "blue", ""),
    "自定义·暗" to Triple(true, "green", SAMPLE_CUSTOM_THEME_JSON)
)

private const val SAMPLE_CUSTOM_THEME_JSON =
    """{"topBar":"#2A2118","topBarText":"#F5E7D5","background":"#221B13","surface":"#2E2419",
       "surfaceAlt":"#3A2E20","primary":"#E8A34D","primaryText":"#1A1208","selectedBackground":"#4A3618",
       "text":"#F0E2CC","textMuted":"#A89578","divider":"#4A3D2B","inputBackground":"#332918",
       "inputBorder":"#5A4A32","icon":"#D8C4A4","danger":"#E86A5A","warning":"#E2B34E",
       "success":"#8FBC6A","disabled":"#6A5C48","overlay":"#99000000"}"""

@Composable
private fun ThemedSheetPreview(
    theme: Triple<Boolean, String, String>,
    heightDp: Int,
    content: @Composable () -> Unit
) {
    AccountTheme(darkTheme = theme.first, accentTheme = theme.second, customThemeJson = theme.third) {
        SheetPagePreview { content() }
    }
}

@Preview(name = "自动填充密码·绿暗", showBackground = true, widthDp = 411, heightDp = 380)
@Composable
private fun AutofillPasswordSheetPreviewGreenDark() {
    ThemedSheetPreview(PreviewThemeMatrix[1].second, 380) {
        AutofillPasswordSheetItems(onConfirm = {}, onCancel = {})
    }
}
