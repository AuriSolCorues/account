package com.copy.account.page

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import com.copy.account.BuildConfig
import com.copy.account.R
import androidx.compose.ui.res.stringResource
import com.copy.account.data.model.MAX_AUTO_LOCK_SECONDS
import com.copy.account.security.MAX_PASSWORD_ITERATIONS
import com.copy.account.security.MIN_PASSWORD_ITERATIONS
import com.copy.account.security.isMasterPasswordValid
import com.copy.account.ui.components.AppListScreen
import com.copy.account.ui.components.NumberSettingDialog
import com.copy.account.ui.components.PasswordField
import com.copy.account.ui.components.SettingsHeader
import com.copy.account.ui.components.SettingsRow
import com.copy.account.ui.components.SettingsSwitchRow
import com.copy.account.ui.components.SurfaceCard
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.theme.AccountTheme
import kotlinx.coroutines.launch

@Composable
internal fun SecuritySettingsScreen(
    biometricEnabled: Boolean,
    biometricAvailable: Boolean,
    onToggleBiometric: (Boolean) -> Unit,
    onChangeMasterPassword: suspend (String) -> Result<Unit>,
    autofillEnabled: Boolean,
    onAutofillEnabledChange: (Boolean) -> Unit,
    onCloseAutofillWindow: () -> Unit,
    onOpenAutofillWindow: () -> Unit,
    autofillWindowOpen: Boolean,
    autoLockSeconds: Int,
    onAutoLockChange: (Int) -> Unit,
    backgroundLockSeconds: Int,
    onBackgroundLockChange: (Int) -> Unit,
    clipboardClearSeconds: Int,
    onClipboardClearChange: (Int) -> Unit,
    kdfIterations: Int,
    onKdfIterationsChange: (Int) -> Unit,
    blockScreenshots: Boolean,
    onBlockScreenshotsChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var showBackgroundLockDialog by remember { mutableStateOf(false) }
    var showClipboardDialog by remember { mutableStateOf(false) }
    var showKdfDialog by remember { mutableStateOf(false) }
    var autofillHelpExpanded by remember { mutableStateOf(false) }
    var windowHelpExpanded by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }
    var confirmNewPassword by remember { mutableStateOf("") }
    var changePasswordError by remember { mutableStateOf("") }
    var changePasswordMessage by remember { mutableStateOf("") }
    val context = LocalContext.current
    val passwordMismatchMsg = stringResource(R.string.security_password_mismatch)
    val passwordChangedMsg = stringResource(R.string.security_password_changed)
    val passwordSaveFailedMsg = stringResource(R.string.security_password_save_failed)
    val masterPasswordHint = stringResource(R.string.master_password_rule_hint)
    val cancelLabel = stringResource(R.string.action_cancel)
    val saveLabel = stringResource(R.string.action_save)
    val lockOffLabel = stringResource(R.string.security_lock_off)
    val lockNowLabel = stringResource(R.string.security_lock_immediately)
    val secondsFmt = stringResource(R.string.duration_seconds)
    val minutesFmt = stringResource(R.string.duration_minutes)
    val hoursFmt = stringResource(R.string.duration_hours)
    AppListScreen(stringResource(R.string.security_title), onBack) {
        item { SettingsHeader(stringResource(R.string.security_privacy_section)) }
        item { SettingsSwitchRow(stringResource(R.string.security_block_screenshots), blockScreenshots, onBlockScreenshotsChange) }
        item { SettingsRow(stringResource(R.string.security_autolock_row), autoLockLabel(autoLockSeconds, lockOffLabel, secondsFmt)) { showAutoLockDialog = true } }
        item { SettingsRow(stringResource(R.string.security_background_lock_row), backgroundLockLabel(backgroundLockSeconds, lockNowLabel, secondsFmt, minutesFmt)) { showBackgroundLockDialog = true } }
        item { SettingsRow(stringResource(R.string.security_clipboard_row), clipboardClearLabel(clipboardClearSeconds, lockOffLabel, secondsFmt, minutesFmt, hoursFmt)) { showClipboardDialog = true } }
        item { SettingsHeader(stringResource(R.string.security_unlock_section)) }
        item {
            SettingsSwitchRow(
                stringResource(R.string.security_biometric_row),
                biometricEnabled,
                onToggleBiometric,
                enabled = biometricAvailable
            )
        }
        item {
            SettingsRow(stringResource(R.string.security_change_password_row), stringResource(R.string.action_open)) {
                newPassword = ""
                confirmNewPassword = ""
                changePasswordError = ""
                showChangePasswordDialog = true
            }
        }
        if (changePasswordMessage.isNotBlank()) {
            item { Text(changePasswordMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        }
        item { SettingsRow(stringResource(R.string.security_kdf_row), "%,d".format(kdfIterations)) { showKdfDialog = true } }
        item { SettingsHeader(stringResource(R.string.security_autofill_section)) }
        item {
            SurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.security_autofill_row))
                    TextActionButton(
                        if (autofillHelpExpanded) "−" else "?",
                        onClick = { autofillHelpExpanded = !autofillHelpExpanded },
                        modifier = Modifier.padding(start = 6.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = autofillEnabled,
                        onCheckedChange = onAutofillEnabledChange
                    )
                }
            }
        }
        if (autofillHelpExpanded) {
            item {
                Text(
                    stringResource(R.string.security_autofill_master_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            SurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.security_autofill_window_row))
                    TextActionButton(
                        if (windowHelpExpanded) "−" else "?",
                        onClick = { windowHelpExpanded = !windowHelpExpanded },
                        modifier = Modifier.padding(start = 6.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = autofillWindowOpen,
                        onCheckedChange = { open -> if (open) onOpenAutofillWindow() else onCloseAutofillWindow() },
                        enabled = autofillEnabled
                    )
                }
            }
        }
        if (windowHelpExpanded) {
            item {
                Text(
                    stringResource(R.string.security_autofill_window_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item { AutofillJumpRow() }
    }
    if (showChangePasswordDialog) AlertDialog(
        onDismissRequest = { showChangePasswordDialog = false },
        title = { Text(stringResource(R.string.security_change_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.security_change_password_hint), style = MaterialTheme.typography.bodySmall)
                PasswordField(stringResource(R.string.security_new_password_label), newPassword, { newPassword = it; changePasswordError = "" }, showPasswordToggle = true)
                PasswordField(stringResource(R.string.security_confirm_password_label), confirmNewPassword, { confirmNewPassword = it; changePasswordError = "" }, showPasswordToggle = true)
                if (changePasswordError.isNotBlank()) Text(changePasswordError, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextActionButton(saveLabel, onClick = {
                when {
                    !isMasterPasswordValid(newPassword) -> changePasswordError = masterPasswordHint
                    newPassword != confirmNewPassword -> changePasswordError = passwordMismatchMsg
                    else -> {
                        scope.launch {
                            val result = onChangeMasterPassword(newPassword)
                            if (result.isSuccess) {
                                showChangePasswordDialog = false
                                newPassword = ""
                                confirmNewPassword = ""
                                changePasswordMessage = passwordChangedMsg
                            } else {
                                changePasswordError = result.exceptionOrNull()?.message ?: passwordSaveFailedMsg
                            }
                        }
                    }
                }
            })
        },
        dismissButton = { TextActionButton(cancelLabel, onClick = { showChangePasswordDialog = false }) }
    )
    if (showAutoLockDialog) NumberSettingDialog(
        title = stringResource(R.string.security_autolock_row),
        unit = stringResource(R.string.security_unit_seconds),
        minValue = 1,
        maxValue = MAX_AUTO_LOCK_SECONDS,
        presets = listOf(
            lockOffLabel to { onAutoLockChange(0) },
            stringResource(R.string.duration_seconds, 15) to { onAutoLockChange(15) },
            stringResource(R.string.duration_seconds, 30) to { onAutoLockChange(30) },
            stringResource(R.string.duration_minutes, 1) to { onAutoLockChange(60) },
            stringResource(R.string.duration_minutes, 5) to { onAutoLockChange(300) },
            stringResource(R.string.duration_minutes, 10) to { onAutoLockChange(600) },
            stringResource(R.string.duration_minutes, 30) to { onAutoLockChange(1800) }
        ),
        onConfirm = { seconds -> onAutoLockChange(seconds.coerceIn(1, MAX_AUTO_LOCK_SECONDS)) },
        onDismiss = { showAutoLockDialog = false }
    )
    if (showBackgroundLockDialog) NumberSettingDialog(
        title = stringResource(R.string.security_background_lock_row),
        unit = stringResource(R.string.security_unit_seconds),
        minValue = 0,
        maxValue = 300,
        presets = listOf(
            lockNowLabel to { onBackgroundLockChange(0) },
            stringResource(R.string.duration_seconds, 5) to { onBackgroundLockChange(5) },
            stringResource(R.string.duration_seconds, 10) to { onBackgroundLockChange(10) },
            stringResource(R.string.duration_seconds, 15) to { onBackgroundLockChange(15) },
            stringResource(R.string.duration_seconds, 30) to { onBackgroundLockChange(30) }
        ),
        onConfirm = { seconds -> onBackgroundLockChange(seconds.coerceIn(0, 300)) },
        onDismiss = { showBackgroundLockDialog = false }
    )
    if (showClipboardDialog) NumberSettingDialog(
        title = stringResource(R.string.security_clipboard_row),
        unit = stringResource(R.string.security_unit_seconds),
        minValue = 5,
        maxValue = 86_400,
        presets = listOf(
            stringResource(R.string.security_clipboard_never) to { onClipboardClearChange(0) },
            stringResource(R.string.duration_seconds, 15) to { onClipboardClearChange(15) },
            stringResource(R.string.duration_seconds, 30) to { onClipboardClearChange(30) },
            stringResource(R.string.duration_minutes, 1) to { onClipboardClearChange(60) }
        ),
        onConfirm = { seconds -> onClipboardClearChange(seconds.coerceIn(0, 86_400)) },
        onDismiss = { showClipboardDialog = false }
    )
    if (showKdfDialog) NumberSettingDialog(
        title = stringResource(R.string.security_kdf_row),
        unit = stringResource(R.string.security_unit_times),
        minValue = MIN_PASSWORD_ITERATIONS,
        maxValue = MAX_PASSWORD_ITERATIONS,
        presets = listOf(
            stringResource(R.string.security_kdf_recommended) to { onKdfIterationsChange(1_000_000) },
            "600,000" to { onKdfIterationsChange(600_000) },
            "500,000" to { onKdfIterationsChange(500_000) },
            "300,000" to { onKdfIterationsChange(300_000) }
        ),
        onConfirm = { iterations -> onKdfIterationsChange(iterations.coerceIn(MIN_PASSWORD_ITERATIONS, MAX_PASSWORD_ITERATIONS)) },
        onDismiss = { showKdfDialog = false }
    )
}

internal fun autoLockLabel(seconds: Int, offLabel: String, secondsFmt: String): String = when {
    seconds <= 0 -> offLabel
    else -> secondsFmt.format(seconds)
}

internal fun backgroundLockLabel(seconds: Int, nowLabel: String, secondsFmt: String, minutesFmt: String): String = when {
    seconds <= 0 -> nowLabel
    seconds < 60 -> secondsFmt.format(seconds)
    seconds % 60 == 0 -> minutesFmt.format(seconds / 60)
    else -> secondsFmt.format(seconds)
}

internal fun clipboardClearLabel(seconds: Int, offLabel: String, secondsFmt: String, minutesFmt: String, hoursFmt: String): String =
    when {
        seconds <= 0 -> offLabel
        seconds < 60 -> secondsFmt.format(seconds)
        seconds % 3600 == 0 -> hoursFmt.format(seconds / 3600)
        seconds % 60 == 0 -> minutesFmt.format(seconds / 60)
        else -> secondsFmt.format(seconds)
    }

@Composable
private fun AutofillJumpRow() {
    val context = LocalContext.current
    SettingsRow(stringResource(R.string.security_jump_autofill_settings), stringResource(R.string.security_jump_action)) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:${context.packageName}"))
            )
        } catch (_: Exception) {
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SecuritySettingsScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE == "system") {
        SecuritySettingsScreen(
            biometricEnabled = false,
            biometricAvailable = true,
            onToggleBiometric = {},
            onChangeMasterPassword = { Result.success(Unit) },
            autofillEnabled = false,
            autofillWindowOpen = false,
            onAutofillEnabledChange = {},
            onCloseAutofillWindow = {},
            onOpenAutofillWindow = {},
            autoLockSeconds = 45,
            onAutoLockChange = {},
            backgroundLockSeconds = 5,
            onBackgroundLockChange = {},
            clipboardClearSeconds = 30,
            onClipboardClearChange = {},
            kdfIterations = 600_000,
            onKdfIterationsChange = {},
            blockScreenshots = false,
            onBlockScreenshotsChange = {},
            onBack = {}
        )
    }
}
