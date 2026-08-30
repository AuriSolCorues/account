package com.copy.account.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.core.crypto.isMasterPasswordValid
import com.copy.account.ui.components.accountTopBarColors
import com.copy.account.BuildConfig
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette
import com.copy.account.ui.theme.SavedTheme
import com.copy.account.ui.theme.defaultThemePresets
import com.copy.account.ui.theme.parseThemeJson

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    biometricEnabled: Boolean,
    biometricAvailable: Boolean,
    onToggleBiometric: (Boolean) -> Unit,
    onChangeMasterPassword: (String) -> Result<Unit>,
    autoLockMinutes: Int,
    onAutoLockChange: (Int) -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    accentTheme: String,
    onAccentThemeChange: (String) -> Unit,
    customThemeJson: String,
    customThemes: List<SavedTheme>,
    languageTag: String,
    onApplyThemeJson: (String) -> Boolean,
    onSaveCustomTheme: (String, String) -> Unit,
    onDeleteCustomTheme: (String) -> Unit,
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
    clipboardClearSeconds: Int,
    onClipboardClearChange: (Int) -> Unit,
    allowScreenshots: Boolean,
    onAllowScreenshotsChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val presets = remember { defaultThemePresets() }
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAccentDialog by remember { mutableStateOf(false) }
    var showJsonDialog by remember { mutableStateOf(false) }
    var showClipboardDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var clipboardDraft by remember { mutableStateOf("") }
    var clipboardError by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmNewPassword by remember { mutableStateOf("") }
    var changePasswordError by remember { mutableStateOf("") }
    var changePasswordMessage by remember { mutableStateOf("") }
    var draftThemeJson by remember(customThemeJson) { mutableStateOf(customThemeJson.ifBlank { presets.first().json }) }
    var jsonError by remember { mutableStateOf("") }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { TopAppBar(colors = accountTopBarColors(), title = { Text("设置", color = LocalAccountThemePalette.current.topBarText) }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回", color = LocalAccountThemePalette.current.topBarText) } }) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SettingsHeader("安全") }
            item { SettingsSwitchRow("允许截图", allowScreenshots, onAllowScreenshotsChange) }
            item { SettingsRow("自动锁定", "$autoLockMinutes 分钟") { showAutoLockDialog = true } }
            item {
                SettingsRow(
                    "生物识别解锁",
                    when {
                        !biometricAvailable -> "设备不支持"
                        biometricEnabled -> "开启"
                        else -> "关闭"
                    },
                    if (biometricAvailable) { { onToggleBiometric(!biometricEnabled) } } else null
                )
            }
            item {
                SettingsRow("修改主密码", "打开") {
                    newPassword = ""
                    confirmNewPassword = ""
                    changePasswordError = ""
                    showChangePasswordDialog = true
                }
            }
            if (changePasswordMessage.isNotBlank()) {
                item { Text(changePasswordMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            }
            item { SettingsHeader("剪贴板与外观") }
            item { SettingsRow("敏感内容自动清除", clipboardClearLabel(clipboardClearSeconds)) { clipboardDraft = ""; clipboardError = ""; showClipboardDialog = true } }
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
            if (customThemes.isNotEmpty()) {
                item { SettingsHeader("已保存的自定义主题") }
                items(customThemes, key = { it.id }) { saved ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { draftThemeJson = saved.json; showJsonDialog = true }, modifier = Modifier.weight(1f), content = { Text(saved.name) })
                        TextButton(onClick = { onDeleteCustomTheme(saved.id) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item { SettingsRow("语言", languageLabel(languageTag)) }
            item { SettingsHeader("数据与备份") }
            item { SettingsRow("备份文件管理", "打开", onOpenBackup) }
        }
    }
    if (showChangePasswordDialog) AlertDialog(
        onDismissRequest = { showChangePasswordDialog = false },
        title = { Text("修改主密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请输入新的主密码。修改后请记住新密码。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it; changePasswordError = "" },
                    label = { Text("新主密码（4-20 个字符）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = confirmNewPassword,
                    onValueChange = { confirmNewPassword = it; changePasswordError = "" },
                    label = { Text("再次输入新主密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (changePasswordError.isNotBlank()) Text(changePasswordError, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    !isMasterPasswordValid(newPassword) -> changePasswordError = "主密码长度需为 4-20 个字符"
                    newPassword != confirmNewPassword -> changePasswordError = "两次输入的主密码不一致"
                    else -> {
                        val result = onChangeMasterPassword(newPassword)
                        if (result.isSuccess) {
                            showChangePasswordDialog = false
                            newPassword = ""
                            confirmNewPassword = ""
                            changePasswordMessage = "主密码已修改"
                        } else {
                            changePasswordError = result.exceptionOrNull()?.message ?: "主密码保存失败，请重试"
                        }
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { showChangePasswordDialog = false }) { Text("取消") } }
    )
    if (showAutoLockDialog) AlertDialog(
        onDismissRequest = { showAutoLockDialog = false },
        title = { Text("自动锁定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1, 5, 10, 30).forEach { minutes ->
                    TextButton(onClick = { onAutoLockChange(minutes); showAutoLockDialog = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("${minutes} 分钟")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showAutoLockDialog = false }) { Text("取消") } }
    )
    if (showClipboardDialog) AlertDialog(
        onDismissRequest = { showClipboardDialog = false },
        title = { Text("剪贴板清除时间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0 to "关闭自动清除", 15 to "15 秒", 30 to "30 秒", 60 to "60 秒").forEach { (seconds, label) ->
                    TextButton(onClick = { onClipboardClearChange(seconds); showClipboardDialog = false }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
                OutlinedTextField(
                    value = clipboardDraft,
                    onValueChange = { clipboardDraft = it.filter(Char::isDigit); clipboardError = "" },
                    label = { Text("自定义秒数（1-86400）") },
                    singleLine = true
                )
                if (clipboardError.isNotBlank()) Text(clipboardError, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val seconds = clipboardDraft.toIntOrNull()
                if (seconds == null || seconds !in 1..86_400) clipboardError = "请输入 1-86400 之间的整数"
                else { onClipboardClearChange(seconds); showClipboardDialog = false }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { showClipboardDialog = false }) { Text("取消") } }
    )
    if (showThemeDialog) AlertDialog(
        onDismissRequest = { showThemeDialog = false },
        title = { Text("颜色主题") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("dark" to "深色", "light" to "浅色", "system" to "跟随系统").forEach { (mode, label) ->
                    TextButton(onClick = { onThemeModeChange(mode); showThemeDialog = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showThemeDialog = false }) { Text("取消") } }
    )
    if (showAccentDialog) AlertDialog(
        onDismissRequest = { showAccentDialog = false },
        title = { Text("配色方案") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("green" to "绿色（设计）", "blue" to "蓝色").forEach { (accent, label) ->
                    TextButton(onClick = { onAccentThemeChange(accent); showAccentDialog = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showAccentDialog = false }) { Text("取消") } }
    )
    if (showJsonDialog) AlertDialog(
        onDismissRequest = { showJsonDialog = false },
        title = { Text("自定义主题 JSONC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("示例主题（可直接载入后修改）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    presets.forEach { preset -> TextButton(onClick = { draftThemeJson = preset.json; jsonError = "" }) { Text(preset.name) } }
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
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("account-theme.jsonc", draftThemeJson))
                    }) { Text("复制 JSON") }
                    TextButton(onClick = {
                        val parsed = parseThemeJson(draftThemeJson)
                        if (parsed == null) jsonError = "JSON 或颜色格式无效" else onSaveCustomTheme(parsed.name, draftThemeJson)
                    }) { Text("保存副本") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onApplyThemeJson(draftThemeJson)) {
                    jsonError = ""
                    showJsonDialog = false
                } else jsonError = "JSON 或颜色格式无效"
            }) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = { showJsonDialog = false }) { Text("取消") } }
    )
}

/** 当前只提供简体中文；未知标签回退中文，后续可直接增加语言资源。 */
internal fun languageLabel(languageTag: String): String = when (languageTag) {
    "zh-CN" -> "简体中文"
    else -> "简体中文"
}

internal fun clipboardClearLabel(seconds: Int): String =
    if (seconds <= 0) "关闭" else "$seconds 秒"

@Composable
internal fun SettingsHeader(text: String) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)) }

@Composable
internal fun SettingsRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    Card(onClick = onClick ?: {}, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, modifier = Modifier.weight(1f)); Text(value, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
internal fun SettingsSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(onClick = { onCheckedChange(!checked) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        SettingsScreen(
            biometricEnabled = false,
            biometricAvailable = true,
            onToggleBiometric = {},
            onChangeMasterPassword = { Result.success(Unit) },
            autoLockMinutes = 5,
            onAutoLockChange = {},
            themeMode = "dark",
            onThemeModeChange = {},
            accentTheme = "green",
            onAccentThemeChange = {},
            customThemeJson = "",
            customThemes = emptyList(),
            languageTag = "zh-CN",
            onApplyThemeJson = { true },
            onSaveCustomTheme = { _, _ -> },
            onDeleteCustomTheme = {},
            onBack = {},
            onOpenBackup = {},
            clipboardClearSeconds = 30,
            onClipboardClearChange = {},
            allowScreenshots = false,
            onAllowScreenshotsChange = {}
        )
    }
}
