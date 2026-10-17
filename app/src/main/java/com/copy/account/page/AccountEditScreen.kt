/**
 * 职责：账号新建/编辑页——全应用可变状态最多的一屏：名称、固定用户名/密码行（可改名/显隐）、
 *       自定义字段（增删/拖动排序）、分组勾选、两步验证（TOTP/HOTP/Steam 全参数 + 实时预览 + 扫码）。
 * 架构位置：AccountApp 的 AppPage.Edit(id?) 分支；保存/删除经 onSave/onDelete 回调上抛，
 *           新建分组经 onCreateGroup 同步拿回 id。
 * Python 类比：整片 remember(source?.id) 同键——键（账号 id）一换全部表单状态重建并预填，
 *           ≈ 切换编辑对象时重开一张空白表单；「模板新建」= 键为 null 但初值取自 template。
 */
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
import com.copy.account.page.PasskeyDisplayEntry
import com.copy.account.ui.components.DeleteConfirmDialog
import com.copy.account.ui.components.DragHandleGlyph
import com.copy.account.ui.components.FieldTextBox
import com.copy.account.ui.components.RandomPasswordGeneratorSheet
import com.copy.account.ui.components.SheetTitleRow
import com.copy.account.ui.components.ScanQrOverlay
import com.copy.account.ui.components.SurfaceCard
import com.copy.account.ui.components.SwitchRow
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.components.TextInputDialog
import com.copy.account.ui.components.rememberClock
import com.copy.account.ui.components.ReorderCardStyle
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette
import kotlinx.coroutines.launch

/** ☷ 短按菜单目标：直接捕获该行的写值/显隐闭包，免做 when 分派。 */
private data class FieldMenuTarget(
    val label: String,
    val hidden: Boolean,
    val fill: (String) -> Unit,
    val onToggleHidden: () -> Unit,
    val onDelete: (() -> Unit)? = null
)

// 尾部共用一槽；缩窄之，输入框得以延长，☷ 仍保有可点击宽度。
private val FieldTrailingSlotWidth = 35.dp

/** TOTP 位数快捷选项；其余位数走「自定义」输入。 */
private val TotpDigitPresets = intArrayOf(5, 6, 8)

/** 描边输入框样式的下拉选择；点击展开 DropdownMenu，选项按 value 回传。 */
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
        // 输入框自身会消费点击（聚焦/光标），外层 clickable 收不到；
        // 在其上盖一层透明点击层来展开下拉。
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
    onDelete: (suspend () -> Result<Unit>)? = null,
    /** 本机通行密钥信息（AccountApp 查注册表下发）：非 null 显示绑定提示与删除入口。 */
    passkeyInfo: PasskeyDisplayEntry? = null,
    onDeletePasskey: ((String) -> Unit)? = null
) {
    val source = account ?: template
    // 表单状态整片以 source?.id 为 remember 键：编辑对象一换（含从模板新建），全部状态重建为初值再预填。
    var name by remember(source?.id) { mutableStateOf(source?.name.orEmpty()) }
    var username by remember(source?.id) { mutableStateOf(source?.username.orEmpty()) }
    var password by remember(source?.id) { mutableStateOf(source?.password.orEmpty()) }
    var usernameHidden by remember(source?.id) { mutableStateOf(source?.usernameHidden ?: false) }
    var passwordHidden by remember(source?.id) { mutableStateOf(source?.passwordHidden ?: true) }
    var usernameLabel by remember(source?.id) { mutableStateOf(source?.usernameLabel.orEmpty()) }
    var passwordLabel by remember(source?.id) { mutableStateOf(source?.passwordLabel.orEmpty()) }
    /** 网址：仅存原文不强制补 scheme；匹配时的清洗交给 DomainCleaner。 */
    var url by remember(source?.id) { mutableStateOf(source?.url.orEmpty()) }
    /** 关联应用包名：非空即自动填充时按该应用匹配；null = 未绑定。 */
    var boundPackage by remember(source?.id) { mutableStateOf(source?.packageName) }
    var showAppPicker by remember { mutableStateOf(false) }
    var hasTotp by remember(source?.id) { mutableStateOf(source?.hasTotp ?: false) }
    var totpSecret by remember(source?.id) { mutableStateOf(source?.totpSecret.orEmpty()) }
    var totpType by remember(source?.id) { mutableStateOf(source?.totpType ?: DEFAULT_TOTP_TYPE) }
    var totpDigits by remember(source?.id) { mutableIntStateOf(source?.totpDigits ?: DEFAULT_TOTP_DIGITS) }
    /** 是否处于「自定义」位数输入状态（区别于 5/6/8 快捷位）。 */
    var totpCustomMode by remember(source?.id) { mutableStateOf(source?.totpDigits?.let { it !in TotpDigitPresets } ?: false) }
    /** 「自定义」输入框内容，实时同步到 totpDigits。 */
    var totpCustomDigits by remember(source?.id) { mutableStateOf(source?.totpDigits?.takeIf { it !in TotpDigitPresets }?.toString() ?: "") }
    var totpPeriodText by remember(source?.id) { mutableStateOf((source?.totpPeriod ?: DEFAULT_TOTP_PERIOD).toString()) }
    var totpCounterText by remember(source?.id) { mutableStateOf((source?.totpCounter ?: 0).toString()) }
    var totpAlgorithm by remember(source?.id) { mutableStateOf(source?.totpAlgorithm ?: DEFAULT_TOTP_ALGORITHM) }
    var totpError by remember(source?.id) { mutableStateOf("") }
    var selectedCustomGroups by remember(account?.id, initialGroupId) {
        mutableStateOf(account?.groups ?: if (groups.any { it.id == initialGroupId && it.kind == GroupKind.CUSTOM }) setOf(initialGroupId) else emptySet())
    }
    var fields by remember(source?.id) { mutableStateOf(source?.customFields ?: emptyList()) }
    /** 最近聚焦行的写值闭包，供底部「随机密码」填回。 */
    var fillTarget by remember(source?.id) { mutableStateOf<(String) -> Unit>({ password = it }) }
    /** ☷ 短按打开的目标行。 */
    var menuTarget by remember(source?.id) { mutableStateOf<FieldMenuTarget?>(null) }
    var addGroupDialog by remember { mutableStateOf(false) }
    var showMissingName by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    /** 通行密钥删除确认框开关（删除入口唯一在编辑页危险区；AccountApp 回调侧再做校验）。 */
    var passkeyDeleteConfirm by remember { mutableStateOf(false) }
    var showPasswordGenerator by remember { mutableStateOf(false) }
    /** 两步验证二维码扫码层开关；打开时盖在当前页上方，编辑状态不丢。 */
    var showScan by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val customGroups = groups.filter { it.kind == GroupKind.CUSTOM }

    // Steam/HOTP 周期固定 30（HOTP 不显示周期，仅占位）；TOTP 读输入框。保存与预览共用。
    fun totpPeriodValue(): Int? = if (totpType == "STEAM" || totpType == "HOTP") 30 else totpPeriodText.toIntOrNull()

    // 粘贴 otpauth:// 链接时自动带出算法/位数/周期/计数器与类型（解析在 security/parseOtpAuth）。
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

    // 保存流程：先逐项校验（名称/周期/位数/计数器），全过才构造 Account 上抛；新建以时间戳当 id。
    fun saveAccount() {
        val isSteam = totpType == "STEAM"
        val isHotp = totpType == "HOTP"
        val counter = totpCounterText.toLongOrNull() ?: -1L
        val period = totpPeriodValue()
        when {
            name.isBlank() -> showMissingName = true
            hasTotp && !isSteam && (period == null || period !in 1..300) -> totpError = "验证码周期需为 1-300 秒"
            hasTotp && !isSteam && totpDigits !in 1..10 -> totpError = "验证码位数需为 1-10"
            hasTotp && isHotp && counter < 0 -> totpError = "计数器需为非负整数"
            else -> {
                val edited = Account(
                    id = account?.id ?: "account-${System.currentTimeMillis()}",
                    // uid：新建/编辑都重新生成（内嵌毫秒=最近修改时间锚）。
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
        if (account == null) "新建账号" else "编辑账号",
        onBack,
        actions = { TextActionButton(if (saving) "保存中" else "保存", ::saveAccount, enabled = !saving, textColor = LocalAccountThemePalette.current.topBarText) },
        spacing = 12.dp
    ) {
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("账号名称 *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Text("分组", style = MaterialTheme.typography.labelLarge)
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
                FilterChip(selected = false, onClick = { addGroupDialog = true }, label = { Text("＋ 增加组") })
            }
            Text(
                "当前进入：${groups.firstOrNull { it.id == initialGroupId }?.name ?: "默认"}；动态密码由 TOTP 自动决定。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            val fill: (String) -> Unit = { username = it }
            FieldEditRow(
                labelValue = usernameLabel,
                onLabelChange = { usernameLabel = it },
                labelPlaceholder = "用户名",
                value = username,
                onValueChange = fill,
                hidden = usernameHidden,
                mask = maskChar,
                onFocused = { fillTarget = fill },
                onHandleMenu = {
                    menuTarget = FieldMenuTarget(
                        label = usernameLabel.ifBlank { "用户名" },
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
                labelPlaceholder = "密码",
                value = password,
                onValueChange = fill,
                hidden = passwordHidden,
                mask = maskChar,
                onFocused = { fillTarget = fill },
                onHandleMenu = {
                    menuTarget = FieldMenuTarget(
                        label = passwordLabel.ifBlank { "密码" },
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
                label = { Text("网址") },
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
                labelPlaceholder = "字段名",
                value = field.value,
                onValueChange = fill,
                hidden = field.hidden,
                mask = maskChar,
                onFocused = { fillTarget = fill },
                onHandleMenu = {
                    menuTarget = FieldMenuTarget(
                        label = field.label.ifBlank { "新字段" },
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
                "短按 ☷ 操作该字段 · 长按行拖动排序（用户名/密码固定、不可拖）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextActionButton(
                    text = "随机密码",
                    onClick = { showPasswordGenerator = true },
                    modifier = Modifier.weight(1f)
                )
                TextActionButton(
                    text = "＋ 新增字段",
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
                "两步验证",
                hasTotp,
                { hasTotp = it },
                subtitle = if (hasTotp) "已配置 · 自动显示在动态密码分组" else "未配置"
            )
        }
        if (hasTotp) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownBox(
                        label = "服务提供",
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
                        label = "加密方式",
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
                        label = "位数",
                        display = if (totpCustomMode) "自定义" else "$totpDigits 位",
                        options = TotpDigitPresets.map { it.toString() to "$it 位" } + ("custom" to "自定义"),
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
                            label = { Text("当前计数") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        OutlinedTextField(
                            value = totpPeriodText,
                            onValueChange = { totpPeriodText = it.filter(Char::isDigit); totpError = "" },
                            label = { Text("验证码周期（秒）") },
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
                        label = { Text("自定义位数（1-10）") },
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
                        label = { Text(if (totpType == "STEAM") "Steam shared_secret（Base64）" else "${if (totpType == "HOTP") "HOTP" else "TOTP"} 密钥（Base32 或 otpauth）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (totpType == "TOTP") {
                        TextActionButton("扫码", onClick = { showScan = true })
                    }
                }
            }
            item {
                // 秒钟与密钥解码都收在本预览行：仅此行重绘，不带动整页输入框，也不逐秒重解密钥。
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
                        totpSecret.isBlank() -> "请输入密钥以生成验证码"
                        decodedSecret.size < 10 -> if (totpType == "STEAM") "Steam shared_secret 格式不正确，请检查 Base64 内容" else "密钥格式不正确，请检查 Base32 内容"
                        else -> "当前验证码：${totpCode(previewAccount, decodedSecret, nowMillis)}"
                    },
                    color = if (decodedSecret.size in 1..9) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }
            if (totpError.isNotBlank()) item { Text(totpError, color = MaterialTheme.colorScheme.error) }
        }
        if (saveError.isNotBlank()) item { Text(saveError, color = MaterialTheme.colorScheme.error) }
        // 危险区：删除本机通行密钥（唯一入口，用户拍板）在删除账号之上。
        if (passkeyInfo != null && onDeletePasskey != null) {
            item { DangerButton("删除本机通行密钥（${passkeyInfo.rpId}）", onClick = { passkeyDeleteConfirm = true }) }
            item {
                Text(
                    "删除后本机不再能以此通行密钥登录 ${passkeyInfo.rpId}；网站侧需自行到账号设置移除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (account != null && onDelete != null) item { DangerButton("删除账号", onClick = { deleteConfirm = true }) }
    }

    menuTarget?.let { target ->
        AppBottomSheet(onDismiss = { menuTarget = null }, skipPartiallyExpanded = true, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetTitleRow(target.label) {}
            ActionSheetRow(if (target.hidden) "显示" else "隐藏") {
                target.onToggleHidden()
                menuTarget = null
            }
            ActionSheetRow("随机密码") {
                fillTarget = target.fill
                menuTarget = null
                showPasswordGenerator = true
            }
            if (target.onDelete != null) {
                ActionSheetRow("删除字段", color = MaterialTheme.colorScheme.error) {
                    target.onDelete?.invoke()
                    menuTarget = null
                }
            }
            ActionSheetRow("取消", muted = true) { menuTarget = null }
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
            title = "新增分组",
            label = "分组名称",
            confirmText = "创建并选择",
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
            title = { Text("缺少账号名称") },
            text = { Text("请输入账号名称后再保存。") },
            confirmButton = { TextActionButton("确定", onClick = { showMissingName = false }) }
        )
    }
    if (passkeyDeleteConfirm && passkeyInfo != null) AlertDialog(
        onDismissRequest = { passkeyDeleteConfirm = false },
        title = { Text("删除本机通行密钥") },
        text = { Text("删除后本机将无法再用指纹登录 ${passkeyInfo.rpId}（网站侧不受影响，需自行到网站设置移除通行密钥）。此操作不可恢复。") },
        confirmButton = {
            TextActionButton("删除", { passkeyDeleteConfirm = false; onDeletePasskey?.invoke(passkeyInfo.credentialId) }, textColor = MaterialTheme.colorScheme.error)
        },
        dismissButton = {
            TextActionButton("取消", { passkeyDeleteConfirm = false })
        }
    )
    if (deleteConfirm) {
        DeleteConfirmDialog(
            title = "删除账号",
            message = "删除「${account?.name.orEmpty()}」后无法恢复。",
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
                // 整串回填；若是 otpauth:// 由上方 LaunchedEffect(totpSecret) 自动带出算法/位数/周期/Steam。
                totpSecret = text
                totpError = ""
                showScan = false
            }
        )
    }
}

/** 应用选择器的条目：仅保留匹配/展示所需三件，不持有 Drawable 图标本体以外的状态。 */
private data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable
)

/** Drawable → ImageBitmap：Bitmap 直取；自适应/矢量图标画到临时位图（应用图标展示足够）。 */
private fun drawableToImageBitmap(drawable: Drawable): ImageBitmap {
    if (drawable is BitmapDrawable) return drawable.bitmap.asImageBitmap()
    val size = 48
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(android.graphics.Canvas(bitmap))
    return bitmap.asImageBitmap()
}

/**
 * 关联应用卡：未绑定显「选择应用」入口，已绑定显图标/应用名/包名 + 解除绑定。
 * 图标与应用名经 PackageManager 现取现显（remember(packageName) 缓存，包名一换重取）。
 */
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
                Text("关联应用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val label = remember(boundPackage) {
                    boundPackage?.let { pkg ->
                        runCatching {
                            val pm = context.packageManager
                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        }.getOrNull()
                    }
                }
                Text(
                    text = label ?: "选择应用",
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
                TextActionButton("解除绑定", onClick = onUnbind)
            }
        }
    }
}

/**
 * 应用选择器：全屏对话框列已安装的可启动应用（Launcher intent 查询），顶部搜索按
 * 应用名与包名过滤；过滤自身与系统安卓壳。列表打开时查一次，图标行内现取现显。
 */
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
                SheetTitleRow("选择应用") {}
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索应用") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                if (filtered.isEmpty()) {
                    Text(
                        "未找到匹配的应用",
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
                TextActionButton("取消", onClick = onDismiss, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

/** 字段行：标签直编 + 值直编（FieldTextBox 自带显隐）+ ☷ 短按菜单。固定行不传 onMove（不可拖/删）。 */
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

/** 一行三件：标签输入 + 值输入 + ☷；三行（用户名/密码/自定义）同一套控件。 */
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

/** 行内小号标签输入：空值显占位，点进直接改名。 */
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
