package com.copy.account.page

import android.annotation.SuppressLint
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copy.account.security.copyToClipboard
import com.copy.account.security.totpCode
import com.copy.account.data.model.Account
import com.copy.account.data.model.Group
import com.copy.account.data.model.GroupKind
import com.copy.account.data.model.initialAccounts
import com.copy.account.data.model.initialGroups
import com.copy.account.ui.components.AccountActionSheet
import com.copy.account.ui.components.AccountPreviewSheet
import com.copy.account.ui.components.DeleteConfirmDialog
import com.copy.account.ui.components.EmptyState
import com.copy.account.ui.components.SurfaceCard
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.components.accountTopBarColors
import com.copy.account.BuildConfig
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    accounts: List<Account>,
    groups: List<Group>,
    selectedGroupId: String,
    clipboardClearSeconds: Int,
    maskChar: Char = '•',
    onGroupSelected: (String) -> Unit,
    onNewAccount: () -> Unit,
    onEditAccount: (String) -> Unit,
    onTemplateNew: (Account) -> Unit,
    onDeleteAccount: (String) -> Unit,
    /** 批量转移：把 ids 账号从 fromGroupId 移到 toGroupId（toGroupId 为默认组时清空全部自定义归属）。 */
    onMoveAccounts: (ids: Set<String>, fromGroupId: String, toGroupId: String) -> Unit,
    onManageGroups: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onHotpAdvance: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var previewAccount by remember { mutableStateOf<Account?>(null) }
    var menuAccount by remember { mutableStateOf<Account?>(null) }
    var deleteConfirmAccount by remember { mutableStateOf<Account?>(null) }
    /** 批量转移模式：长按分组进入；列表锁定源组账号供勾选，点侧栏其他分组为目标。 */
    var batchGroupId by remember { mutableStateOf<String?>(null) }
    var batchSelectedIds by remember { mutableStateOf(emptySet<String>()) }
    var moveTargetGroup by remember { mutableStateOf<Group?>(null) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(100)
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val selectedGroup = groups.firstOrNull { it.id == selectedGroupId } ?: groups.first()
    val batchSourceGroup = batchGroupId?.let { id -> groups.firstOrNull { it.id == id } }
    val showTotpOnCards = (batchSourceGroup ?: selectedGroup).kind == GroupKind.DYNAMIC
    // 批量转移时列源分组账号；搜索跨全部分组；否则按当前分组过滤。
    val visibleAccounts = when {
        batchSourceGroup != null -> accounts.filter { accountInGroup(it, groups, batchSourceGroup.id) }
        searchQuery.isNotBlank() -> accounts.filter { accountMatchesSearch(it, groups, searchQuery) }
        else -> accounts.filter { accountInGroup(it, groups, selectedGroup.id) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = accountTopBarColors(),
                title = {
                    if (searchOpen) {
                        // 搜索框落在顶栏底色上，字色/占位/光标/边框全部用 topBarText 显式着色，
                        // 否则自定义主题（顶栏与全局明暗不一致）下会看不见。
                        val palette = LocalAccountThemePalette.current
                        OutlinedTextField(
                            searchQuery,
                            { searchQuery = it },
                            placeholder = { Text("搜索账号") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.topBarText),
                            colors = OutlinedTextFieldDefaults.colors(
                                cursorColor = palette.topBarText,
                                focusedTextColor = palette.topBarText,
                                unfocusedTextColor = palette.topBarText,
                                focusedPlaceholderColor = palette.topBarText.copy(alpha = 0.55f),
                                unfocusedPlaceholderColor = palette.topBarText.copy(alpha = 0.55f),
                                focusedBorderColor = palette.topBarText.copy(alpha = 0.7f),
                                unfocusedBorderColor = palette.topBarText.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else Text("账号本子", color = LocalAccountThemePalette.current.topBarText, fontWeight = FontWeight.Bold)
                },
                actions = {
                    IconButton(onClick = onNewAccount) { Text("＋", color = LocalAccountThemePalette.current.topBarText, fontSize = 24.sp) }
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) searchQuery = "" }) { Text("⌕", color = LocalAccountThemePalette.current.topBarText, fontSize = 22.sp) }
                    IconButton(onClick = onOpenSettings) { Text("☰", color = LocalAccountThemePalette.current.topBarText, fontSize = 20.sp) }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val sidebarWidth = (maxWidth * 0.24f).coerceIn(72.dp, 112.dp)
            Row(modifier = Modifier.fillMaxSize()) {
                // 左栏按窗口宽度自适应，保留三字以上的最小可读空间，不绑定某台手机像素。
                GroupSidebar(
                    groups = groups,
                    // 勾选模式中源组保持高亮，指示当前列表来源。
                    selectedGroupId = batchGroupId ?: selectedGroupId,
                    onGroupTap = { id ->
                        val source = batchSourceGroup
                        if (source == null) onGroupSelected(id)
                        else {
                            // 勾选模式：点侧栏其他分组即选目标；源组与动态密码组不可为目标。
                            val target = groups.firstOrNull { it.id == id }
                            if (target != null && target.id != source.id && target.kind != GroupKind.DYNAMIC && batchSelectedIds.isNotEmpty()) {
                                moveTargetGroup = target
                            }
                        }
                    },
                    onGroupLongPress = { id ->
                        batchGroupId = id
                        batchSelectedIds = emptySet()
                    },
                    onManageGroups = onManageGroups,
                    modifier = Modifier.width(sidebarWidth).fillMaxHeight()
                )
                Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp)) {
                    if (batchSourceGroup != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("从「${batchSourceGroup.name}」转移 · 已选 ${batchSelectedIds.size} 个", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                Text("点左侧其他分组作为目标", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            TextActionButton("取消", onClick = { batchGroupId = null; batchSelectedIds = emptySet() })
                        }
                    } else {
                        Text(
                            if (searchQuery.isNotBlank()) "搜索「${searchQuery.trim()}」 · ${visibleAccounts.size} 条"
                            else "${selectedGroup.name} · ${visibleAccounts.size} 条",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                    if (visibleAccounts.isEmpty()) {
                        EmptyState(
                            when {
                                batchSourceGroup != null -> "该分组暂无账号"
                                searchQuery.isNotBlank() -> "无匹配账号"
                                selectedGroup.kind == GroupKind.DYNAMIC -> "在账号编辑页添加两步验证"
                                selectedGroup.kind == GroupKind.DEFAULT -> "暂无未分组账号"
                                else -> "暂无账号"
                            },
                            Modifier.fillMaxWidth().weight(1f)
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(visibleAccounts, key = { it.id }) { account ->
                                // 勾选模式：点卡片即选/取消（点击时实时读集合，勿捕获布尔快照），速览与长按菜单让位。
                                AccountCard(
                                    account,
                                    showTotpOnCards,
                                    nowMillis,
                                    selected = account.id in batchSelectedIds,
                                    onClick = {
                                        if (batchSourceGroup != null) {
                                            batchSelectedIds = if (account.id in batchSelectedIds) batchSelectedIds - account.id else batchSelectedIds + account.id
                                        } else previewAccount = account
                                    },
                                    onLongClick = { if (batchSourceGroup == null) menuAccount = account }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    previewAccount?.let { account -> AccountPreviewSheet(account, clipboardClearSeconds, { previewAccount = null }, { previewAccount = null; onEditAccount(account.id) }, { previewAccount = null; onOpenDetail(account.id) }, maskChar = maskChar, onHotpAdvance = { onHotpAdvance(account.id) }) }
    menuAccount?.let { account ->
        AccountActionSheet(
            account = account,
            onDismiss = { menuAccount = null },
            onEdit = { onEditAccount(account.id) },
            onDelete = { menuAccount = null; deleteConfirmAccount = account },
            onTemplateNew = { onTemplateNew(account) },
            onCopyAll = { copyToClipboard(context, accountCopyableText(account), sensitive = true, clearAfterSeconds = clipboardClearSeconds) }
        )
    }
    deleteConfirmAccount?.let { account ->
        DeleteConfirmDialog(
            title = "删除账号",
            message = "确定删除「${account.name}」吗？此操作不可撤销。",
            onConfirm = {
                onDeleteAccount(account.id)
                if (previewAccount?.id == account.id) previewAccount = null
                deleteConfirmAccount = null
            },
            onDismiss = { deleteConfirmAccount = null }
        )
    }
    // 转移确认：拦住侧栏误触，确认后执行并退出勾选模式。
    moveTargetGroup?.let { target ->
        val source = batchSourceGroup
        if (source != null) AlertDialog(
            onDismissRequest = { moveTargetGroup = null },
            title = { Text("转移账号") },
            text = {
                Text(
                    "把已选 ${batchSelectedIds.size} 个账号转移到「${target.name}」" +
                        if (target.kind == GroupKind.DEFAULT) "？（移入默认分组将清空其全部自定义分组归属）" else "？"
                )
            },
            confirmButton = {
                TextActionButton("确认", onClick = {
                    onMoveAccounts(batchSelectedIds, source.id, target.id)
                    moveTargetGroup = null
                    batchGroupId = null
                    batchSelectedIds = emptySet()
                }, textColor = MaterialTheme.colorScheme.primary)
            },
            dismissButton = { TextActionButton("取消", onClick = { moveTargetGroup = null }) }
        )
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GroupSidebar(
    groups: List<Group>,
    selectedGroupId: String,
    onGroupTap: (String) -> Unit,
    onGroupLongPress: (String) -> Unit,
    onManageGroups: () -> Unit,
    modifier: Modifier
) {
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                groups.forEach { group ->
                    val selected = group.id == selectedGroupId
                    val selectionColor by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        label = "group-selection-color"
                    )
                    Surface(
                        color = selectionColor,
                        shape = RoundedCornerShape(6.dp),
                        // combinedClickable：回调跨重组更新，勾选模式下点分组选目标才不会拿到陈旧闭包。
                        modifier = Modifier
                            .fillMaxWidth()
                            // 同 AccountCard：未选中不挂 border，避免零宽 stroke 发丝线。
                            .then(if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier)
                            .combinedClickable(onClick = { onGroupTap(group.id) }, onLongClick = { onGroupLongPress(group.id) })
                    ) {
                        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp))
                    }
                }
            }
        }
        HorizontalDivider()
        TextActionButton("⚙ 分组管理", onManageGroups, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AccountCard(
    account: Account,
    showTotp: Boolean,
    nowMillis: Long,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // combinedClickable 而非 pointerInput：手势 lambda 跨重组自动更新，
    // 避免勾选切换等依赖最新状态的回调被首次组合的陈旧闭包冻结。
    // 边框用 then 条件挂载：border(0.dp) 零宽 stroke 在 Skia 成发丝线，未选中仍会露边。
    SurfaceCard(modifier = Modifier
        .fillMaxWidth()
        .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // HOTP 是事件型（无时间倒计时），只在复制时进位，卡片上不画进度条。
            val hotp = account.hasTotp && account.totpType.equals("HOTP", ignoreCase = true)
            if (showTotp && account.hasTotp && !hotp) {
                val period = account.totpPeriod.coerceAtLeast(1)
                val periodMillis = period * 1000L
                val elapsedFraction = (nowMillis % periodMillis).toFloat() / periodMillis
                val remainingFraction = (1f - elapsedFraction).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(remainingFraction)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(account.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = when {
                    !(showTotp && account.hasTotp) -> account.username
                    hotp -> "${totpCode(account, nowMillis)}  ·  HOTP"
                    else -> "${totpCode(account, nowMillis)}  ·  ${account.totpPeriod - (nowMillis / 1000L % account.totpPeriod)} 秒"
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 判断账号是否属于某个分组（默认=未分组、动态=已配置 TOTP、自定义=显式关联）。 */
internal fun accountInGroup(account: Account, groups: List<Group>, groupId: String): Boolean {
    val group = groups.firstOrNull { it.id == groupId } ?: return false
    return when (group.kind) {
        GroupKind.DEFAULT -> account.groups.isEmpty()
        GroupKind.DYNAMIC -> account.hasTotp
        GroupKind.CUSTOM -> groupId in account.groups
    }
}

/** 搜索跨账号名称、用户名、固定行自定义名（如「支付密码」）、自定义字段与所属分组名匹配。 */
internal fun accountMatchesSearch(account: Account, groups: List<Group>, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    return account.name.contains(q, true) ||
        account.username.contains(q, true) ||
        account.usernameLabel.orEmpty().contains(q, true) ||
        account.passwordLabel.orEmpty().contains(q, true) ||
        account.customFields.any { it.label.contains(q, true) || it.value.contains(q, true) } ||
        account.groups.any { gid -> groups.firstOrNull { it.id == gid }?.name?.contains(q, true) == true }
}

/** 汇总账号全部可复制字段（名称、用户名、密码与自定义字段），用于“复制账号全部内容”。 */
internal fun accountCopyableText(account: Account): String = buildList {
    add("名称：${account.name}")
    add("用户名：${account.username}")
    add("密码：${account.password}")
    account.customFields.forEach { add("${it.label}：${it.value}") }
}.joinToString("\n")

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        HomeScreen(
            accounts = initialAccounts,
            groups = initialGroups,
            selectedGroupId = "default",
            clipboardClearSeconds = 30,
            onGroupSelected = {},
            onNewAccount = {},
            onEditAccount = {},
            onTemplateNew = {},
            onDeleteAccount = {},
            onMoveAccounts = { _, _, _ -> },
            onManageGroups = {},
            onOpenSettings = {},
            onOpenDetail = {}
        )
    }
}
