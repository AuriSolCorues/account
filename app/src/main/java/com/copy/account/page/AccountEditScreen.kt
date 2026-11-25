package com.copy.account.page

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.copy.account.BuildConfig
import com.copy.account.R
import androidx.compose.ui.res.stringResource
import com.copy.account.data.model.DEFAULT_TOTP_ALGORITHM
import com.copy.account.data.model.DEFAULT_TOTP_DIGITS
import com.copy.account.data.model.DEFAULT_TOTP_PERIOD
import com.copy.account.data.model.DEFAULT_TOTP_TYPE
import com.copy.account.data.model.newUid
import com.copy.account.security.decodeSecret
import com.copy.account.security.normalizedTotpSecret
import com.copy.account.security.parseOtpAuth
import com.copy.account.security.totpCode
import com.copy.account.data.model.Account
import com.copy.account.data.model.AccountField
import com.copy.account.data.model.Group
import com.copy.account.data.model.GroupKind
import com.copy.account.data.model.initialAccounts
import com.copy.account.data.model.initialGroups
import com.copy.account.ui.components.AppListScreen
import com.copy.account.ui.components.ActionSheetRow
import com.copy.account.ui.components.AnimatedReorderCard
import com.copy.account.ui.components.AppBottomSheet
import com.copy.account.ui.components.DangerButton
import com.copy.account.ui.components.DeleteConfirmDialog
import com.copy.account.ui.components.DragHandleGlyph
import com.copy.account.ui.components.FieldTextBox
import com.copy.account.ui.components.RandomPasswordGeneratorSheet
import com.copy.account.ui.components.SheetTitleRow
import com.copy.account.ui.components.ScanQrOverlay
import com.copy.account.ui.components.SurfaceCard
import com.copy.account.ui.components.SwitchRow
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.components.TopBarTextButton
import com.copy.account.ui.components.TextInputDialog
import com.copy.account.ui.components.rememberClock
import com.copy.account.ui.components.ReorderCardStyle
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette
import kotlinx.coroutines.launch

private data class FieldMenuTarget(
    val label: String,
    val hidden: Boolean,
    val fill: (String) -> Unit,
    val onToggleHidden: () -> Unit,
    val onDelete: (() -> Unit)? = null
)

private val FieldTrailingSlotWidth = 35.dp

private val TotpDigitPresets = intArrayOf(5, 6, 8)

@Composable
private fun DropdownBox(
    label: String,
    display: String,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { Text(if (expanded) "▴" else "▾", modifier = Modifier.padding(end = 12.dp)) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable(enabled = enabled) { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountEditScreen(
    account: Account?,
    template: Account?,
    groups: List<Group>,
    initialGroupId: String,
    clipboardClearSeconds: Int,
    maskChar: Char = '•',
    onBack: () -> Unit,
    onCreateGroup: (String) -> String,
    onSave: suspend (Account) -> Result<Unit>,
    onDelete: (suspend () -> Result<Unit>)? = null
) {
    val source = account ?: template
    var name by remember(source?.id) { mutableStateOf(source?.name.orEmpty()) }
    var username by remember(source?.id) { mutableStateOf(source?.username.orEmpty()) }
    var password by remember(source?.id) { mutableStateOf(source?.password.orEmpty()) }
    var usernameHidden by remember(source?.id) { mutableStateOf(source?.usernameHidden ?: false) }
    var passwordHidden by remember(source?.id) { mutableStateOf(source?.passwordHidden ?: true) }
    var usernameLabel by remember(source?.id) { mutableStateOf(source?.usernameLabel.orEmpty()) }
    var passwordLabel by remember(source?.id) { mutableStateOf(source?.passwordLabel.orEmpty()) }
    var url by remember(source?.id) { mutableStateOf(source?.url.orEmpty()) }
    var boundPackage by remember(source?.id) { mutableStateOf(source?.packageName) }
    var showAppPicker by remember { mutableStateOf(false) }
    var hasTotp by remember(source?.id) { mutableStateOf(source?.hasTotp ?: false) }
    var totpSecret by remember(source?.id) { mutableStateOf(source?.totpSecret.orEmpty()) }
    var totpType by remember(source?.id) { mutableStateOf(source?.totpType ?: DEFAULT_TOTP_TYPE) }
    var totpDigits by remember(source?.id) { mutableIntStateOf(source?.totpDigits ?: DEFAULT_TOTP_DIGITS) }
    var totpCustomMode by remember(source?.id) { mutableStateOf(source?.totpDigits?.let { it !in TotpDigitPresets } ?: false) }
    var totpCustomDigits by remember(source?.id) { mutableStateOf(source?.totpDigits?.takeIf { it !in TotpDigitPresets }?.toString() ?: "") }
    var totpPeriodText by remember(source?.id) { mutableStateOf((source?.totpPeriod ?: DEFAULT_TOTP_PERIOD).toString()) }
    var totpCounterText by remember(source?.id) { mutableStateOf((source?.totpCounter ?: 0).toString()) }
    var totpAlgorithm by remember(source?.id) { mutableStateOf(source?.totpAlgorithm ?: DEFAULT_TOTP_ALGORITHM) }
    var totpError by remember(source?.id) { mutableStateOf("") }
    var selectedCustomGroups by remember(account?.id, initialGroupId) {
        mutableStateOf(account?.groups ?: if (groups.any { it.id == initialGroupId && it.kind == GroupKind.CUSTOM }) setOf(initialGroupId) else emptySet())
    }
    var fields by remember(source?.id) { mutableStateOf(source?.customFields ?: emptyList()) }
    var fillTarget by remember(source?.id) { mutableStateOf<(String) -> Unit>({ password = it }) }
    var menuTarget by remember(source?.id) { mutableStateOf<FieldMenuTarget?>(null) }
    var addGroupDialog by remember { mutableStateOf(false) }
    var showMissingName by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var showPasswordGenerator by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val customGroups = groups.filter { it.kind == GroupKind.CUSTOM }
    val totpPeriodError = stringResource(R.string.edit_error_totp_period)
    val totpDigitsError = stringResource(R.string.edit_error_totp_digits)
    val totpCounterError = stringResource(R.string.edit_error_totp_counter)
    val secretEmptyError = stringResource(R.string.edit_error_secret_empty)
    val steamSecretError = stringResource(R.string.edit_error_steam_secret)
    val secretFormatError = stringResource(R.string.edit_error_secret_format)
    val nameMissingTitle = stringResource(R.string.edit_error_name_missing)
    val deleteTitle = stringResource(R.string.edit_delete_account)
    val cancelLabel = stringResource(R.string.action_cancel)
    val savingLabel = stringResource(R.string.edit_saving)
    val saveLabel = stringResource(R.string.action_save)
    val usernamePlaceholder = stringResource(R.string.edit_username_label)
    val passwordPlaceholder = stringResource(R.string.edit_password_label)
    val fieldNamePlaceholder = stringResource(R.string.edit_field_name_label)
    val newFieldPlaceholder = stringResource(R.string.edit_new_field)
    val randomPasswordLabel = stringResource(R.string.edit_random_password)
    val addFieldLabel = stringResource(R.string.edit_add_field)
    val totpPreviewFmt = stringResource(R.string.edit_totp_preview_code)

    fun totpPeriodValue(): Int? = if (totpType == "STEAM" || totpType == "HOTP") 30 else totpPeriodText.toIntOrNull()

    LaunchedEffect(totpSecret) {
        val params = parseOtpAuth(totpSecret) ?: return@LaunchedEffect
        params.algorithm?.let { totpAlgorithm = it }
        params.digits?.let { value ->
            totpDigits = value
            totpCustomMode = value !in TotpDigitPresets
            totpCustomDigits = if (value !in TotpDigitPresets) value.toString() else ""
        }
        params.counter?.let { totpCounterText = it.toString() }
        params.period?.let { totpPeriodText = it.toString() }
        params.type?.let { totpType = it }
    }

    fun moveField(id: String, direction: Int) {
        val from = fields.indexOfFirst { it.id == id }
        val target = from + direction
        if (from !in fields.indices || target !in fields.indices) return
        fields = fields.toMutableList().also { it.add(target, it.removeAt(from)) }
    }

    fun saveAccount() {
        val isSteam = totpType == "STEAM"
        val isHotp = totpType == "HOTP"
        val counter = totpCounterText.toLongOrNull() ?: -1L
        val period = totpPeriodValue()
        when {
            name.isBlank() -> showMissingName = true
            hasTotp && !isSteam && (period == null || period !in 1..300) -> totpError = totpPeriodError
            hasTotp && !isSteam && totpDigits !in 1..10 -> totpError = totpDigitsError
            hasTotp && isHotp && counter < 0 -> totpError = totpCounterError
            else -> {
                val edited = Account(
                    id = account?.id ?: "account-${System.currentTimeMillis()}",
                    uid = newUid(),
                    name = name.trim(),
                    username = username,
                    password = password,
                    usernameLabel = usernameLabel.trim().ifBlank { null },
                    passwordLabel = passwordLabel.trim().ifBlank { null },
                    usernameHidden = usernameHidden,
                    passwordHidden = passwordHidden,
                    groups = selectedCustomGroups,
                    hasTotp = hasTotp,
                    totpSecret = normalizedTotpSecret(totpSecret.trim()),
                    totpDigits = totpDigits,
                    totpPeriod = period ?: 30,
                    totpCounter = if (isHotp) counter else 0,
                    totpAlgorithm = totpAlgorithm,
                    customFields = fields,
                    url = url.trim(),
                    packageName = boundPackage,
                    totpType = totpType
                )
                scope.launch {
                    saving = true
                    val result = onSave(edited)
                    saving = false
                    saveError = result.exceptionOrNull()?.message ?: ""
                }
            }
        }
    }

    AppListScreen(
        if (account == null) stringResource(R.string.edit_title_new) else stringResource(R.string.edit_title_edit),
        onBack,
        actions = { TopBarTextButton(if (saving) savingLabel else saveLabel, ::saveAccount, enabled = !saving) },
        spacing = 12.dp
    ) {
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.edit_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Text(stringResource(R.string.edit_group_label), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                customGroups.forEach { group ->
                    FilterChip(
                        selected = group.id in selectedCustomGroups,
                        onClick = {
                            selectedCustomGroups = if (group.id in selectedCustomGroups) {
                                selectedCustomGroups - group.id
                            } else {
                                selectedCustomGroups + group.id
                            }
                        },
                        label = { Text(group.name) }
                    )
                }
                FilterChip(selected = false, onClick = { addGroupDialog = true }, label = { Text(stringResource(R.string.edit_add_group)) })
            }
            Text(
                stringResource(
                    R.string.edit_group_dynamic_note_fmt,
                    groups.firstOrNull { it.id == initialGroupId }?.name ?: stringResource(R.string.detail_group_default)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            val fill: (String) -> Unit = { username = it }
            FieldEditRow(
                labelValue = usernameLabel,
                onLabelChange = { usernameLabel = it },
                labelPlaceholder = usernamePlaceholder,
                value = username,
                onValueChange = fill,
                hidden = usernameHidden,
                mask = maskChar,
                onFocused = { fillTarget = fill },
                onHandleMenu = {
                    menuTarget = FieldMenuTarget(
                        label = usernameLabel.ifBlank { usernamePlaceholder },
                        hidden = usernameHidden,
                        fill = fill,
                        onToggleHidden = { usernameHidden = !usernameHidden }
                    )
                }
            )
        }
        item {
            val fill: (String) -> Unit = { password = it }
            FieldEditRow(
                labelValue = passwordLabel,
                onLabelChange = { passwordLabel = it },
                labelPlaceholder = passwordPlaceholder,
                value = password,
                onValueChange = fill,
                hidden = passwordHidden,
                mask = maskChar,
                onFocused = { fillTarget = fill },
                onHandleMenu = {
                    menuTarget = FieldMenuTarget(
                        label = passwordLabel.ifBlank { passwordPlaceholder },
                        hidden = passwordHidden,
                        fill = fill,
                        onToggleHidden = { passwordHidden = !passwordHidden }
                    )
                }
            )
        }
        item {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.edit_url_label)) },
                placeholder = { Text("https://example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            BoundAppCard(
                boundPackage = boundPackage,
                onPick = { showAppPicker = true },
                onUnbind = { boundPackage = null }
            )
        }
        items(fields, key = { it.id }) { field ->
            val fill: (String) -> Unit = { value -> fields = fields.map { if (it.id == field.id) it.copy(value = value) else it } }
            FieldEditRow(
                labelValue = field.label,
                onLabelChange = { label -> fields = fields.map { if (it.id == field.id) it.copy(label = label) else it } },
                labelPlaceholder = fieldNamePlaceholder,
                value = field.value,
                onValueChange = fill,
                hidden = field.hidden,
                mask = maskChar,
                onFocused = { fillTarget = fill },
                onHandleMenu = {
                    menuTarget = FieldMenuTarget(
                        label = field.label.ifBlank { newFieldPlaceholder },
                        hidden = field.hidden,
                        fill = fill,
                        onToggleHidden = { fields = fields.map { if (it.id == field.id) it.copy(hidden = !it.hidden) else it } },
                        onDelete = { fields = fields.filterNot { it.id == field.id } }
                    )
                },
                dragKey = field.id,
                onMove = { moveField(field.id, it) }
            )
        }
        item {
            Text(
                stringResource(R.string.edit_fields_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextActionButton(
                    text = randomPasswordLabel,
                    onClick = { showPasswordGenerator = true },
                    modifier = Modifier.weight(1f)
                )
                TextActionButton(
                    text = addFieldLabel,
                    onClick = {
                        val id = "field-${System.currentTimeMillis()}"
                        fields = fields + AccountField(id, "", "", false)
                        fillTarget = { value -> fields = fields.map { if (it.id == id) it.copy(value = value) else it } }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            SwitchRow(
                stringResource(R.string.edit_totp_section),
                hasTotp,
                { hasTotp = it },
                subtitle = if (hasTotp) stringResource(R.string.edit_totp_configured) else stringResource(R.string.edit_totp_unset)
            )
        }
        if (hasTotp) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownBox(
                        label = stringResource(R.string.edit_totp_issuer),
                        display = when (totpType) {
                            "STEAM" -> "Steam Guard"
                            "HOTP" -> "HOTP"
                            else -> "TOTP"
                        },
                        options = listOf(
                            "TOTP" to "Google TOTP",
                            "STEAM" to "Steam Guard",
                            "HOTP" to "HOTP"
                        ),
                        modifier = Modifier.weight(1f),
                        onSelect = { totpType = it }
                    )
                    DropdownBox(
                        label = stringResource(R.string.edit_totp_algorithm),
                        display = totpAlgorithm,
                        options = listOf("SHA1", "SHA256", "SHA512").map { it to it },
                        enabled = totpType != "STEAM",
                        modifier = Modifier.weight(1f),
                        onSelect = { totpAlgorithm = it }
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownBox(
                        label = stringResource(R.string.edit_totp_digits_row),
                        display = if (totpCustomMode) stringResource(R.string.edit_totp_digits_custom) else stringResource(R.string.edit_totp_digits_value, totpDigits),
                        options = TotpDigitPresets.map { it.toString() to stringResource(R.string.edit_totp_digits_value, it) } + ("custom" to stringResource(R.string.edit_totp_digits_custom)),
                        enabled = totpType != "STEAM",
                        modifier = Modifier.weight(1f),
                        onSelect = { value ->
                            if (value == "custom") {
                                totpCustomMode = true
                                totpCustomDigits = totpDigits.toString()
                            } else {
                                totpCustomMode = false
                                totpCustomDigits = ""
                                totpDigits = value.toInt()
                            }
                        }
                    )
                    if (totpType == "HOTP") {
                        OutlinedTextField(
                            value = totpCounterText,
                            onValueChange = { totpCounterText = it.filter(Char::isDigit); totpError = "" },
                            label = { Text(stringResource(R.string.edit_totp_counter_row)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        OutlinedTextField(
                            value = totpPeriodText,
                            onValueChange = { totpPeriodText = it.filter(Char::isDigit); totpError = "" },
                            label = { Text(stringResource(R.string.edit_totp_period_row)) },
                            enabled = totpType != "STEAM",
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            if (totpCustomMode && totpType != "STEAM") {
                item {
                    OutlinedTextField(
                        value = totpCustomDigits,
                        onValueChange = { input ->
                            val clean = input.filter(Char::isDigit).take(2)
                            totpCustomDigits = clean
                            totpDigits = clean.toIntOrNull() ?: 0
                            totpError = ""
                        },
                        label = { Text(stringResource(R.string.edit_totp_custom_digits_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = totpSecret,
                        onValueChange = { totpSecret = it; totpError = "" },
                        label = { Text(if (totpType == "STEAM") stringResource(R.string.edit_steam_secret_label) else stringResource(R.string.edit_totp_secret_fmt, if (totpType == "HOTP") "HOTP" else "TOTP")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (totpType == "TOTP") {
                        TextActionButton(stringResource(R.string.edit_scan_action), onClick = { showScan = true })
                    }
                }
            }
            item {
                val nowMillis = rememberClock()
                val normalizedSecret = remember(totpSecret) { normalizedTotpSecret(totpSecret) }
                val decodedSecret = remember(normalizedSecret, totpType) { decodeSecret(normalizedSecret, totpType == "STEAM") }
                val previewAccount = remember(totpDigits, totpPeriodText, totpCounterText, totpAlgorithm, totpType, normalizedSecret) {
                    Account(
                        "preview",
                        name,
                        username,
                        password,
                        hasTotp = true,
                        totpSecret = normalizedSecret,
                        totpDigits = totpDigits,
                        totpPeriod = totpPeriodValue() ?: DEFAULT_TOTP_PERIOD,
                        totpCounter = totpCounterText.toLongOrNull() ?: 0,
                        totpAlgorithm = totpAlgorithm,
                        totpType = totpType
                    )
                }
                Text(
                    when {
                        totpSecret.isBlank() -> secretEmptyError
                        decodedSecret.size < 10 -> if (totpType == "STEAM") steamSecretError else secretFormatError
                        else -> totpPreviewFmt.format(totpCode(previewAccount, decodedSecret, nowMillis))
                    },
                    color = if (decodedSecret.size in 1..9) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }
            if (totpError.isNotBlank()) item { Text(totpError, color = MaterialTheme.colorScheme.error) }
        }
        if (saveError.isNotBlank()) item { Text(saveError, color = MaterialTheme.colorScheme.error) }
        if (account != null && onDelete != null) item { DangerButton(deleteTitle, onClick = { deleteConfirm = true }) }
    }

    menuTarget?.let { target ->
        AppBottomSheet(onDismiss = { menuTarget = null }, skipPartiallyExpanded = true, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetTitleRow(target.label) {}
            ActionSheetRow(if (target.hidden) stringResource(R.string.edit_show_field) else stringResource(R.string.edit_hide_field)) {
                target.onToggleHidden()
                menuTarget = null
            }
            ActionSheetRow(randomPasswordLabel) {
                fillTarget = target.fill
                menuTarget = null
                showPasswordGenerator = true
            }
            if (target.onDelete != null) {
                ActionSheetRow(stringResource(R.string.edit_delete_field), color = MaterialTheme.colorScheme.error) {
                    target.onDelete?.invoke()
                    menuTarget = null
                }
            }
            ActionSheetRow(cancelLabel, muted = true) { menuTarget = null }
        }
    }

    if (showPasswordGenerator) {
        RandomPasswordGeneratorSheet(
            onDismiss = { showPasswordGenerator = false },
            onFill = { generated ->
                fillTarget(generated)
                showPasswordGenerator = false
            },
            clipboardClearSeconds = clipboardClearSeconds
        )
    }
    if (addGroupDialog) {
        TextInputDialog(
            title = stringResource(R.string.edit_group_dialog_title),
            label = stringResource(R.string.edit_group_dialog_label),
            confirmText = stringResource(R.string.edit_group_dialog_confirm),
            validate = { it.isNotBlank() && customGroups.none { group -> group.name == it.trim() } },
            onConfirm = { selectedCustomGroups = selectedCustomGroups + onCreateGroup(it); addGroupDialog = false },
            onDismiss = { addGroupDialog = false }
        )
    }
    if (showAppPicker) {
        AppPickerDialog(
            currentPackage = boundPackage,
            onSelect = { picked ->
                boundPackage = picked.packageName
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false }
        )
    }
    if (showMissingName) {
        AlertDialog(
            onDismissRequest = { showMissingName = false },
            title = { Text(nameMissingTitle) },
            text = { Text(stringResource(R.string.edit_error_name_missing_detail)) },
            confirmButton = { TextActionButton(stringResource(R.string.action_ok), onClick = { showMissingName = false }) }
        )
    }
    if (deleteConfirm) {
        DeleteConfirmDialog(
            title = deleteTitle,
            message = stringResource(R.string.edit_delete_confirm_message, account?.name.orEmpty()),
            onConfirm = {
                deleteConfirm = false
                scope.launch {
                    saving = true
                    val result = onDelete?.invoke()
                    saving = false
                    saveError = result?.exceptionOrNull()?.message ?: ""
                }
            },
            onDismiss = { deleteConfirm = false }
        )
    }
    if (showScan) {
        ScanQrOverlay(
            onClose = { showScan = false },
            onScanned = { text ->
                totpSecret = text
                totpError = ""
                showScan = false
            }
        )
    }
}

private data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable
)

private fun drawableToImageBitmap(drawable: Drawable): ImageBitmap {
    if (drawable is BitmapDrawable) return drawable.bitmap.asImageBitmap()
    val size = 48
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(android.graphics.Canvas(bitmap))
    return bitmap.asImageBitmap()
}

@Composable
private fun BoundAppCard(
    boundPackage: String?,
    onPick: () -> Unit,
    onUnbind: () -> Unit
) {
    val context = LocalContext.current
    SurfaceCard(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val iconBitmap = remember(boundPackage) {
                boundPackage?.let { pkg ->
                    runCatching { drawableToImageBitmap(context.packageManager.getApplicationIcon(pkg)) }.getOrNull()
                }
            }
            if (iconBitmap != null) {
                Image(bitmap = iconBitmap, contentDescription = null, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.edit_app_link_section), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val label = remember(boundPackage) {
                    boundPackage?.let { pkg ->
                        runCatching {
                            val pm = context.packageManager
                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        }.getOrNull()
                    }
                }
                Text(
                    text = label ?: stringResource(R.string.edit_pick_app),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (boundPackage != null) {
                    Text(
                        text = boundPackage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (boundPackage != null) {
                TextActionButton(stringResource(R.string.edit_unbind_app), onClick = onUnbind)
            }
        }
    }
}

@Composable
private fun AppPickerDialog(
    currentPackage: String?,
    onSelect: (AppEntry) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val apps = remember {
        runCatching {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .asSequence()
                .map { AppEntry(label = it.loadLabel(pm).toString(), packageName = it.activityInfo.packageName, icon = it.loadIcon(pm)) }
                .distinctBy { it.packageName }
                .filterNot { it.packageName == context.packageName || it.packageName == "android" }
                .sortedBy { it.label.lowercase() }
                .toList()
        }.getOrDefault(emptyList())
    }
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                SheetTitleRow(stringResource(R.string.edit_pick_app)) {}
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.edit_search_apps)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                if (filtered.isEmpty()) {
                    Text(
                        stringResource(R.string.edit_no_matching_apps),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        textAlign = TextAlign.Center
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            val iconBitmap = remember(app.packageName) {
                                runCatching { drawableToImageBitmap(context.packageManager.getApplicationIcon(app.packageName)) }.getOrNull()
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { onSelect(app) }.padding(vertical = 10.dp, horizontal = 4.dp)
                            ) {
                                if (iconBitmap != null) {
                                    Image(bitmap = iconBitmap, contentDescription = null, modifier = Modifier.size(36.dp))
                                    Spacer(Modifier.width(12.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (app.packageName == currentPackage) {
                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextActionButton(stringResource(R.string.action_cancel), onClick = onDismiss, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun LazyItemScope.FieldEditRow(
    labelValue: String,
    onLabelChange: (String) -> Unit,
    labelPlaceholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    hidden: Boolean,
    mask: Char,
    onFocused: () -> Unit,
    onHandleMenu: () -> Unit,
    dragKey: Any = labelPlaceholder,
    onMove: ((Int) -> Unit)? = null
) {
    if (onMove == null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            FieldEditInner(labelValue, onLabelChange, labelPlaceholder, value, onValueChange, hidden, mask, onFocused, onHandleMenu)
        }
        return
    }
    AnimatedReorderCard(
        key = dragKey,
        style = ReorderCardStyle(
            normalColor = LocalAccountThemePalette.current.surface,
            draggingColor = LocalAccountThemePalette.current.selectedBackground,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 3.dp)
        ),
        onMove = onMove
    ) { dragModifier, isDragging, paddingValues ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(paddingValues).then(dragModifier)
        ) {
            FieldEditInner(labelValue, onLabelChange, labelPlaceholder, value, onValueChange, hidden, mask, onFocused, onHandleMenu, dragging = isDragging)
        }
    }
}

@Composable
private fun RowScope.FieldEditInner(
    labelValue: String,
    onLabelChange: (String) -> Unit,
    labelPlaceholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    hidden: Boolean,
    mask: Char,
    onFocused: () -> Unit,
    onHandleMenu: () -> Unit,
    dragging: Boolean = false
) {
    LabelInputField(labelValue, onLabelChange, labelPlaceholder, dragging)
    FieldTextBox(
        value = value,
        onValueChange = onValueChange,
        hidden = hidden,
        mask = mask,
        modifier = Modifier.weight(1f),
        onFocused = onFocused
    )
    Box(modifier = Modifier.width(FieldTrailingSlotWidth).padding(start = 6.dp), contentAlignment = Alignment.CenterEnd) {
        DragHandleGlyph(isDragging = dragging, modifier = Modifier.clickable(onClick = onHandleMenu))
    }
}

@Composable
private fun RowScope.LabelInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    dragging: Boolean
) {
    val textColor = if (dragging) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = Modifier.width(90.dp).padding(end = 6.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AccountEditScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE == "system") {
        AccountEditScreen(
            account = initialAccounts.first(),
            template = null,
            groups = initialGroups,
            initialGroupId = "default",
            clipboardClearSeconds = 30,
            onBack = {},
            onCreateGroup = { "preview" },
            onSave = { Result.success(Unit) },
            onDelete = { Result.success(Unit) }
        )
    }
}
