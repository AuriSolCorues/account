package com.copy.account.feature.edit

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.core.crypto.decodeSecret
import com.copy.account.core.crypto.normalizedTotpSecret
import com.copy.account.core.crypto.totpCode
import com.copy.account.core.security.copyToClipboard
import com.copy.account.data.model.Account
import com.copy.account.data.model.AccountField
import com.copy.account.data.model.Group
import com.copy.account.data.model.GroupKind
import com.copy.account.data.model.initialAccounts
import com.copy.account.data.model.initialGroups
import com.copy.account.ui.components.AccountFieldItem
import com.copy.account.ui.components.DangerButton
import com.copy.account.ui.components.DeleteConfirmDialog
import com.copy.account.ui.components.PasswordField
import com.copy.account.ui.components.RandomPasswordGeneratorSheet
import com.copy.account.ui.components.SwitchRow
import com.copy.account.ui.components.TextInputDialog
import com.copy.account.ui.components.accountTopBarColors
import com.copy.account.ui.components.rememberClock
import com.copy.account.BuildConfig
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette
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
    onSave: (Account) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    // 模板新建时以模板字段初始化，但仍作为新账号保存（新 id、沿用当前分组）。
    val source = account ?: template
    var name by remember(source?.id) { mutableStateOf(source?.name.orEmpty()) }
    var username by remember(source?.id) { mutableStateOf(source?.username.orEmpty()) }
    var password by remember(source?.id) { mutableStateOf(source?.password.orEmpty()) }
    var hasTotp by remember(source?.id) { mutableStateOf(source?.hasTotp ?: false) }
    var totpSecret by remember(source?.id) { mutableStateOf(source?.totpSecret.orEmpty()) }
    var totpType by remember(source?.id) { mutableStateOf(source?.totpType ?: "TOTP") }
    var totpDigits by remember(source?.id) { mutableIntStateOf(source?.totpDigits ?: 6) }
    var totpPeriodText by remember(source?.id) { mutableStateOf((source?.totpPeriod ?: 30).toString()) }
    var totpAlgorithm by remember(source?.id) { mutableStateOf(source?.totpAlgorithm ?: "SHA1") }
    var totpError by remember(source?.id) { mutableStateOf("") }
    val nowMillis = rememberClock()
    LaunchedEffect(totpSecret) {
        val uri = runCatching { Uri.parse(totpSecret.trim()) }.getOrNull()
        if (uri?.scheme == "otpauth") {
            uri.getQueryParameter("algorithm")?.uppercase()?.replace("-", "")?.let { value ->
                if (value in setOf("SHA1", "SHA256", "SHA512")) totpAlgorithm = value
            }
            uri.getQueryParameter("digits")?.toIntOrNull()?.let { value ->
                if (value == 6 || value == 8) totpDigits = value
            }
            uri.getQueryParameter("period")?.toIntOrNull()?.let { value ->
                if (value in 1..300) totpPeriodText = value.toString()
            }
            if (uri.path.orEmpty().contains("steam", ignoreCase = true) || uri.getQueryParameter("issuer").orEmpty().contains("steam", ignoreCase = true)) totpType = "STEAM"
        }
    }
    var selectedCustomGroups by remember(account?.id, initialGroupId) { mutableStateOf(account?.groups ?: if (groups.any { it.id == initialGroupId && it.kind == GroupKind.CUSTOM }) setOf(initialGroupId) else emptySet()) }
    var fields by remember(source?.id) { mutableStateOf(source?.customFields ?: emptyList()) }
    var addFieldHidden by remember { mutableStateOf<Boolean?>(null) }
    var addGroupDialog by remember { mutableStateOf(false) }
    var showMissingName by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var showPasswordGenerator by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val customGroups = groups.filter { it.kind == GroupKind.CUSTOM }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(colors = accountTopBarColors(), title = { Text(if (account == null) "新建账号" else "编辑账号", color = LocalAccountThemePalette.current.topBarText) }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回", color = LocalAccountThemePalette.current.topBarText) } }, actions = {
            TextButton(onClick = {
                val period = if (totpType == "STEAM") 30 else totpPeriodText.toIntOrNull()
                when {
                    name.isBlank() -> showMissingName = true
                    hasTotp && (period == null || period !in 1..300) -> totpError = "验证码周期需为 1-300 秒"
                    else -> onSave(Account(id = account?.id ?: "account-${System.currentTimeMillis()}", name = name.trim(), username = username, password = password, groups = selectedCustomGroups, hasTotp = hasTotp, totpSecret = normalizedTotpSecret(totpSecret.trim()), totpDigits = totpDigits, totpPeriod = period ?: 30, totpAlgorithm = totpAlgorithm, customFields = fields, totpType = totpType))
                }
            }) { Text("保存", color = LocalAccountThemePalette.current.topBarText) }
        })
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OutlinedTextField(name, { name = it }, label = { Text("账号名称 *") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item {
                Text("分组", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    customGroups.forEach { group ->
                        FilterChip(selected = group.id in selectedCustomGroups, onClick = { selectedCustomGroups = if (group.id in selectedCustomGroups) selectedCustomGroups - group.id else selectedCustomGroups + group.id }, label = { Text(group.name) })
                    }
                    FilterChip(selected = false, onClick = { addGroupDialog = true }, label = { Text("＋ 增加组") })
                }
                Text("当前进入：${groups.firstOrNull { it.id == initialGroupId }?.name ?: "默认"}；动态密码由 TOTP 自动决定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { AccountFieldItem("用户名", username, false, onValueChange = { username = it }, mask = maskChar) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    PasswordField(password, { password = it }, label = { Text("密码") }, mask = maskChar, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showPasswordGenerator = true }) { Text("随机") }
                }
            }
            items(fields, key = { it.id }) { field ->
                AccountFieldItem(field.label, field.value, field.hidden, { value -> fields = fields.map { if (it.id == field.id) it.copy(value = value) else it } }, { fields = fields.filterNot { it.id == field.id } }, mask = maskChar)
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick = { addFieldHidden = false }) { Text("＋ 添加字段") }; TextButton(onClick = { addFieldHidden = true }) { Text("＋ 添加隐藏字段") } } }
            item { SwitchRow("两步验证", hasTotp, { hasTotp = it }, subtitle = if (hasTotp) "已配置 · 自动显示在动态密码分组" else "未配置") }
            if (hasTotp) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = totpType == "TOTP", onClick = { totpType = "TOTP" }, label = { Text("Google TOTP") })
                        FilterChip(selected = totpType == "STEAM", onClick = { totpType = "STEAM" }, label = { Text("Steam Guard") })
                    }
                }
                item { OutlinedTextField(totpSecret, { totpSecret = it; totpError = "" }, label = { Text(if (totpType == "STEAM") "Steam shared_secret（Base64）" else "TOTP 密钥（Base32 或 otpauth）") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                if (totpType == "TOTP") {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("SHA1", "SHA256", "SHA512").forEach { algorithm ->
                                FilterChip(selected = totpAlgorithm == algorithm, onClick = { totpAlgorithm = algorithm }, label = { Text(algorithm) })
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(6, 8).forEach { digits ->
                                FilterChip(selected = totpDigits == digits, onClick = { totpDigits = digits }, label = { Text("$digits 位") })
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = totpPeriodText,
                        onValueChange = { totpPeriodText = it.filter(Char::isDigit); totpError = "" },
                        label = { Text(if (totpType == "STEAM") "Steam 周期固定 30 秒" else "验证码周期（1-300 秒）") },
                        enabled = totpType != "STEAM",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    val normalizedSecret = normalizedTotpSecret(totpSecret)
                    val previewAccount = Account("preview", name, username, password, hasTotp = true, totpSecret = normalizedSecret, totpDigits = totpDigits, totpPeriod = if (totpType == "STEAM") 30 else totpPeriodText.toIntOrNull() ?: 30, totpAlgorithm = totpAlgorithm, totpType = totpType)
                    val decodedLength = decodeSecret(normalizedSecret, totpType == "STEAM").size
                    Text(
                        when {
                            totpSecret.isBlank() -> "请输入密钥以生成验证码"
                            decodedLength < 10 -> if (totpType == "STEAM") "Steam shared_secret 格式不正确，请检查 Base64 内容" else "TOTP 密钥格式不正确，请检查 Base32 内容"
                            else -> "当前验证码：${totpCode(previewAccount, nowMillis)}"
                        },
                        color = if (decodedLength in 1..9) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
                if (totpError.isNotBlank()) item { Text(totpError, color = MaterialTheme.colorScheme.error) }
            }
            if (account != null && onDelete != null) {
                item { DangerButton("删除账号", onClick = { deleteConfirm = true }) }
            }
        }
    }

    addFieldHidden?.let { hidden ->
        var label by remember(hidden) { mutableStateOf("") }
        var value by remember(hidden) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addFieldHidden = null }, title = { Text(if (hidden) "添加隐藏字段" else "添加字段") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(label, { label = it }, label = { Text("字段名称") }, singleLine = true); OutlinedTextField(value, { value = it }, label = { Text("字段内容") }, singleLine = true) } }, confirmButton = { TextButton(enabled = label.isNotBlank(), onClick = { fields = fields + AccountField("field-${System.currentTimeMillis()}", label.trim(), value, hidden); addFieldHidden = null }) { Text("添加") } }, dismissButton = { TextButton(onClick = { addFieldHidden = null }) { Text("取消") } })
    }
    if (addGroupDialog) {
        TextInputDialog(
            title = "新增分组",
            label = "分组名称",
            confirmText = "创建并选择",
            validate = { it.isNotBlank() && customGroups.none { g -> g.name == it.trim() } },
            onConfirm = { selectedCustomGroups = selectedCustomGroups + onCreateGroup(it); addGroupDialog = false },
            onDismiss = { addGroupDialog = false }
        )
    }
    if (showMissingName) AlertDialog(onDismissRequest = { showMissingName = false }, title = { Text("缺少账号名称") }, text = { Text("请输入账号名称后再保存。") }, confirmButton = { TextButton(onClick = { showMissingName = false }) { Text("确定") } })
    if (deleteConfirm) {
        DeleteConfirmDialog(
            title = "删除账号",
            message = "确定删除「${account?.name ?: ""}」吗？此操作不可撤销。",
            onConfirm = { deleteConfirm = false; onDelete?.invoke() },
            onDismiss = { deleteConfirm = false }
        )
    }
    if (showPasswordGenerator) {
        RandomPasswordGeneratorSheet(
            onDismiss = { showPasswordGenerator = false },
            onFill = { value ->
                password = value
                copyToClipboard(context, value, sensitive = true, clearAfterSeconds = clipboardClearSeconds)
                showPasswordGenerator = false
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AccountEditScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        AccountEditScreen(
            account = initialAccounts.first(),
            template = null,
            groups = initialGroups,
            initialGroupId = "default",
            clipboardClearSeconds = 30,
            onBack = {},
            onCreateGroup = { "custom-preview" },
            onSave = {},
            onDelete = {}
        )
    }
}
