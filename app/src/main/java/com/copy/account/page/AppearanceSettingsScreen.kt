/**
 * 职责：主题与语言二级页——明暗模式、配色方案、自定义主题 JSONC 编辑与已存主题管理、语言（只读）、
 *       appsettings.json 配置文件重载。
 * 架构位置：AccountApp 的 AppPage.AppearanceSettings 分支（由设置中枢进入）；行组件来自
 *           ui/components/Rows.kt，单选弹窗复用 ui/components/Dialogs.kt 的 ChoiceDialog。
 * Python 类比：同 SecuritySettingsScreen——设置中枢按职责切出的一块，草稿状态仍留在本页局部。
 */
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.BuildConfig
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
    /** 上一次「重新加载配置文件」是否读到了外挂配置；null/false 都显示未使用。 */
    externalConfigLoaded: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val presets = remember { defaultThemePresets() }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAccentDialog by remember { mutableStateOf(false) }
    var showJsonDialog by remember { mutableStateOf(false) }
    var jsonError by remember { mutableStateOf("") }
    // 草稿以 customThemeJson 为键：外部主题一变草稿随之重置，避免旧草稿悄悄盖掉新主题。
    var draftThemeJson by remember(customThemeJson) { mutableStateOf(customThemeJson.ifBlank { presets.first().json }) }
    AppListScreen("主题与语言", onBack) {
        item {
            SettingsRow(
                "明暗模式",
                when (themeMode) {
                    "light" -> "浅色"
                    "system" -> "跟随系统"
                    else -> "深色"
                }
            ) { showThemeDialog = true }
        }
        item {
            SettingsRow("配色方案", if (accentTheme == "blue") "蓝色" else "绿色（设计）") { showAccentDialog = true }
        }
        item {
            SettingsRow("自定义主题 JSON", if (customThemeJson.isBlank()) "未启用" else "已启用") {
                draftThemeJson = customThemeJson.ifBlank { presets.first().json }
                jsonError = ""
                showJsonDialog = true
            }
        }
        item {
            SettingsRow("重新加载配置文件", "打开") {
                onReloadSettings()
                Toast.makeText(context, "配置已重新加载", Toast.LENGTH_SHORT).show()
            }
        }
        item {
            // 该文件是应用级覆盖层，不止主题；写明覆盖范围，免得只改到安全项时找不到入口。
            Text(
                "appsettings.json · 位于备份文件夹内，与 .acc 同级 · 可覆盖主题、语言与安全默认值",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            // 措辞保持中性：「没权限」与「文件不存在」都归到未使用外挂配置，
            // 两种原因在语义上等价（都回落到 DataStore 真值），不必也不该区分。
            SettingsRow("当前生效", if (externalConfigLoaded) "外挂配置已加载" else "未使用外挂配置")
        }
        if (customThemes.isNotEmpty()) {
            item { SettingsHeader("已保存的自定义主题") }
            items(customThemes, key = { it.id }) { saved ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextActionButton(saved.name, onClick = { draftThemeJson = saved.json; showJsonDialog = true }, modifier = Modifier.weight(1f))
                    DangerButton("删除", onClick = { onDeleteCustomTheme(saved.id) })
                }
            }
        }
        item { SettingsHeader("语言") }
        item { SettingsRow("语言", "简体中文") }
    }
    if (showThemeDialog) ChoiceDialog(
        "颜色主题",
        listOf("dark" to "深色", "light" to "浅色", "system" to "跟随系统").map { (mode, label) -> label to { onThemeModeChange(mode) } }
    ) { showThemeDialog = false }
    if (showAccentDialog) ChoiceDialog(
        "配色方案",
        listOf("green" to "绿色（设计）", "blue" to "蓝色").map { (accent, label) -> label to { onAccentThemeChange(accent) } }
    ) { showAccentDialog = false }
    if (showJsonDialog) AlertDialog(
        onDismissRequest = { showJsonDialog = false },
        title = { Text("自定义主题 JSONC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("示例主题（可直接载入后修改）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    presets.forEach { preset -> TextActionButton(preset.name, onClick = { draftThemeJson = preset.json; jsonError = "" }) }
                }
                OutlinedTextField(
                    value = draftThemeJson,
                    onValueChange = { draftThemeJson = it; jsonError = "" },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp),
                    minLines = 10,
                    maxLines = 20,
                    label = { Text("主题配置（支持 // 和 /* */ 注释）") }
                )
                if (jsonError.isNotBlank()) Text(jsonError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextActionButton("复制 JSON", onClick = {
                        // 非敏感（主题配置），不清除；sensitive=false 时 clearAfterSeconds 不生效，传 0 占位。
                        copyToClipboard(context, draftThemeJson, sensitive = false, clearAfterSeconds = 0)
                    })
                    TextActionButton("保存副本", onClick = {
                        val parsed = parseThemeJson(draftThemeJson)
                        if (parsed == null) jsonError = "JSON 或颜色格式无效" else onSaveCustomTheme(parsed.name, draftThemeJson)
                    })
                }
            }
        },
        confirmButton = {
            TextActionButton("应用", onClick = {
                if (onApplyThemeJson(draftThemeJson)) {
                    jsonError = ""
                    showJsonDialog = false
                } else jsonError = "JSON 或颜色格式无效"
            })
        },
        dismissButton = { TextActionButton("取消", onClick = { showJsonDialog = false }) }
    )
}

@Preview(showBackground = true)
@Composable
private fun AppearanceSettingsScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
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
