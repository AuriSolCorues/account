package com.copy.account.page

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.BuildConfig
import com.copy.account.R
import com.copy.account.data.model.SavedTheme
import com.copy.account.ui.components.AppListScreen
import com.copy.account.ui.components.ChoiceDialog
import com.copy.account.ui.components.DangerButton
import com.copy.account.ui.components.SettingsHeader
import com.copy.account.ui.components.SettingsRow
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.platform.copyToClipboard
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.defaultThemePresets
import com.copy.account.ui.theme.parseThemeJson

@Composable
internal fun AppearanceSettingsScreen(
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    accentTheme: String,
    onAccentThemeChange: (String) -> Unit,
    customThemeJson: String,
    customThemes: List<SavedTheme>,
    onApplyThemeJson: (String) -> Boolean,
    onSaveCustomTheme: (String, String) -> Unit,
    onDeleteCustomTheme: (String) -> Unit,
    onReloadSettings: () -> Unit,
    externalConfigLoaded: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val presets = remember { defaultThemePresets() }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAccentDialog by remember { mutableStateOf(false) }
    var showJsonDialog by remember { mutableStateOf(false) }
    var jsonError by remember { mutableStateOf("") }
    var draftThemeJson by remember(customThemeJson) { mutableStateOf(customThemeJson.ifBlank { presets.first().json }) }
    val deleteConfirmLabel = stringResource(R.string.action_cancel)
    val reloadToast = stringResource(R.string.appearance_reload_done)
    val invalidJsonError = stringResource(R.string.appearance_json_invalid)
    AppListScreen(stringResource(R.string.appearance_title), onBack) {
        item {
            SettingsRow(
                stringResource(R.string.appearance_mode_row),
                when (themeMode) {
                    "light" -> stringResource(R.string.appearance_mode_light)
                    "system" -> stringResource(R.string.appearance_mode_system)
                    else -> stringResource(R.string.appearance_mode_dark)
                }
            ) { showThemeDialog = true }
        }
        item {
            SettingsRow(stringResource(R.string.appearance_accent_row), if (accentTheme == "blue") stringResource(R.string.appearance_accent_blue) else stringResource(R.string.appearance_accent_green)) { showAccentDialog = true }
        }
        item {
            SettingsRow(stringResource(R.string.appearance_custom_json_row), if (customThemeJson.isBlank()) stringResource(R.string.appearance_custom_off) else stringResource(R.string.appearance_custom_on)) {
                draftThemeJson = customThemeJson.ifBlank { presets.first().json }
                jsonError = ""
                showJsonDialog = true
            }
        }
        item {
            SettingsRow(stringResource(R.string.appearance_reload_row), stringResource(R.string.action_open)) {
                onReloadSettings()
                Toast.makeText(context, reloadToast, Toast.LENGTH_SHORT).show()
            }
        }
        item {
            Text(
                stringResource(R.string.appearance_appsettings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            SettingsRow(stringResource(R.string.appearance_external_row), if (externalConfigLoaded) stringResource(R.string.appearance_external_on) else stringResource(R.string.appearance_external_off))
        }
        if (customThemes.isNotEmpty()) {
            item { SettingsHeader(stringResource(R.string.appearance_saved_themes_header)) }
            items(customThemes, key = { it.id }) { saved ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextActionButton(saved.name, onClick = { draftThemeJson = saved.json; showJsonDialog = true }, modifier = Modifier.weight(1f))
                    DangerButton(stringResource(R.string.action_delete), onClick = { onDeleteCustomTheme(saved.id) })
                }
            }
        }
        item { SettingsHeader(stringResource(R.string.appearance_language_header)) }
        item { SettingsRow(stringResource(R.string.appearance_language_header), stringResource(R.string.appearance_language_value)) }
    }
    if (showThemeDialog) ChoiceDialog(
        stringResource(R.string.appearance_theme_dialog_title),
        listOf(
            "dark" to stringResource(R.string.appearance_mode_dark),
            "light" to stringResource(R.string.appearance_mode_light),
            "system" to stringResource(R.string.appearance_mode_system)
        ).map { (mode, label) -> label to { onThemeModeChange(mode) } }
    ) { showThemeDialog = false }
    if (showAccentDialog) ChoiceDialog(
        stringResource(R.string.appearance_accent_row),
        listOf(
            "green" to stringResource(R.string.appearance_accent_green),
            "blue" to stringResource(R.string.appearance_accent_blue)
        ).map { (accent, label) -> label to { onAccentThemeChange(accent) } }
    ) { showAccentDialog = false }
    if (showJsonDialog) AlertDialog(
        onDismissRequest = { showJsonDialog = false },
        title = { Text(stringResource(R.string.appearance_json_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.appearance_json_preset_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    presets.forEach { preset -> TextActionButton(preset.name, onClick = { draftThemeJson = preset.json; jsonError = "" }) }
                }
                OutlinedTextField(
                    value = draftThemeJson,
                    onValueChange = { draftThemeJson = it; jsonError = "" },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp),
                    minLines = 10,
                    maxLines = 20,
                    label = { Text(stringResource(R.string.appearance_json_label)) }
                )
                if (jsonError.isNotBlank()) Text(jsonError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextActionButton(stringResource(R.string.appearance_json_copy), onClick = {
                        copyToClipboard(context, draftThemeJson, sensitive = false, clearAfterSeconds = 0)
                    })
                    TextActionButton(stringResource(R.string.appearance_json_save_copy), onClick = {
                        val parsed = parseThemeJson(draftThemeJson)
                        if (parsed == null) jsonError = invalidJsonError else onSaveCustomTheme(parsed.name, draftThemeJson)
                    })
                }
            }
        },
        confirmButton = {
            TextActionButton(stringResource(R.string.appearance_json_apply), onClick = {
                if (onApplyThemeJson(draftThemeJson)) {
                    jsonError = ""
                    showJsonDialog = false
                } else jsonError = invalidJsonError
            })
        },
        dismissButton = { TextActionButton(deleteConfirmLabel, onClick = { showJsonDialog = false }) }
    )
}

@Preview(showBackground = true)
@Composable
private fun AppearanceSettingsScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE == "system") {
        AppearanceSettingsScreen(
            themeMode = "dark",
            onThemeModeChange = {},
            accentTheme = "green",
            onAccentThemeChange = {},
            customThemeJson = "",
            customThemes = emptyList(),
            onApplyThemeJson = { true },
            onSaveCustomTheme = { _, _ -> },
            onDeleteCustomTheme = {},
            onReloadSettings = {},
            externalConfigLoaded = false,
            onBack = {}
        )
    }
}
