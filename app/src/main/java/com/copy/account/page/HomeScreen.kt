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
import androidx.compose.ui.Alignment
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copy.account.data.model.Account
import com.copy.account.data.model.Group
import com.copy.account.data.model.GroupKind
import com.copy.account.data.model.accountInGroup
import com.copy.account.data.model.initialAccounts
import com.copy.account.data.model.initialGroups
import com.copy.account.data.model.sortAccounts
import com.copy.account.security.isHotp
import com.copy.account.security.totpCode
import com.copy.account.ui.components.AccountActionSheet
import com.copy.account.ui.components.AccountPreviewSheet
import com.copy.account.ui.components.DeleteConfirmDialog
import com.copy.account.ui.components.EmptyState
import com.copy.account.ui.components.SurfaceCard
import com.copy.account.ui.platform.copyToClipboard
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.components.accountTopBarColors
import com.copy.account.ui.components.rememberClock
import com.copy.account.BuildConfig
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    accounts: List<Account>,
    groups: List<Group>,
    selectedGroupId: String,
    clipboardClearSeconds: Int,
    maskChar: Char = '•',
    previewAccountId: String? = null,
    onPreviewAccountIdChange: (String?) -> Unit = {},
    onGroupSelected: (String) -> Unit,
    onNewAccount: () -> Unit,
    onEditAccount: (String) -> Unit,
    onTemplateNew: (Account) -> Unit,
    onDeleteAccount: (String) -> Unit,
    onDeleteAccounts: (Set<String>) -> Unit = {},
    onMoveAccounts: (ids: Set<String>, fromGroupId: String, toGroupId: String) -> Unit,
    onManageGroups: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onHotpAdvance: (String) -> Unit = {},
    passkeyAccountIds: Set<String> = emptySet(),
    onScanImport: () -> Unit = {}
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    var menuAccount by remember { mutableStateOf<Account?>(null) }
    var deleteConfirmAccount by remember { mutableStateOf<Account?>(null) }
    var batchGroupId by remember { mutableStateOf<String?>(null) }
    var batchSelectedIds by remember { mutableStateOf(emptySet<String>()) }
    var moveTargetGroup by remember { mutableStateOf<Group?>(null) }
    var deleteConfirmIds by remember { mutableStateOf<Set<String>?>(null) }
    val cancelBatchMove = {
        moveTargetGroup = null
        batchGroupId = null
        batchSelectedIds = emptySet()
    }
    LaunchedEffect(searchOpen) {
        if (searchOpen) searchFocus.requestFocus()
    }
    BackHandler(enabled = moveTargetGroup != null || batchGroupId != null || searchOpen) {
        when {
            moveTargetGroup != null -> moveTargetGroup = null
            batchGroupId != null -> cancelBatchMove()
            searchOpen -> {
                searchOpen = false
                searchQuery = ""
            }
        }
    }
    val selectedGroup = groups.firstOrNull { it.id == selectedGroupId } ?: groups.first()
    val batchSourceGroup = batchGroupId?.let { id -> groups.firstOrNull { it.id == id } }
    val showTotpOnCards = (batchSourceGroup ?: selectedGroup).kind == GroupKind.DYNAMIC
    val visibleAccounts = remember(accounts, groups, selectedGroupId, searchQuery, batchGroupId) {
        val filtered = when {
            batchSourceGroup != null -> accounts.filter { accountInGroup(it, groups, batchSourceGroup.id) }
            searchQuery.isNotBlank() -> accounts.filter { accountMatchesSearch(it, groups, searchQuery) }
            else -> accounts.filter { accountInGroup(it, groups, selectedGroup.id) }
        }
        sortAccounts(filtered)
    }
    val nowMillis = rememberClock(if (showTotpOnCards && visibleAccounts.any { it.hasTotp }) 1_000L else 60_000L)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = accountTopBarColors(),
                title = {
                    if (searchOpen) {
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
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus)
                        )
                    } else Text("账号本子", color = LocalAccountThemePalette.current.topBarText, fontWeight = FontWeight.Bold)
                },
                actions = {
                    IconButton(onClick = onNewAccount) { Text("＋", color = LocalAccountThemePalette.current.topBarText, fontSize = 24.sp) }
                    IconButton(onClick = onScanImport) { Text("⎙", color = LocalAccountThemePalette.current.topBarText, fontSize = 22.sp) }
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) searchQuery = "" }) { Text("⌕", color = LocalAccountThemePalette.current.topBarText, fontSize = 22.sp) }
                    IconButton(onClick = onOpenSettings) { Text("☰", color = LocalAccountThemePalette.current.topBarText, fontSize = 20.sp) }
                }
            )
        }
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val sidebarWidth = (maxWidth * 0.24f).coerceIn(72.dp, 112.dp)
            Row(modifier = Modifier.fillMaxSize()) {
                GroupSidebar(
                    groups = groups,
                    selectedGroupId = batchGroupId ?: selectedGroupId,
                    onGroupTap = { id ->
                        val source = batchSourceGroup
                        if (source == null) onGroupSelected(id)
                        else {
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
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text("从「${batchSourceGroup.name}」转移 · 已选 ${batchSelectedIds.size} 个", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            Text("点左侧其他分组作为目标", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextActionButton(
                                    "删除",
                                    onClick = { deleteConfirmIds = batchSelectedIds },
                                    enabled = batchSelectedIds.isNotEmpty(),
                                    textColor = MaterialTheme.colorScheme.error
                                )
                                TextActionButton("取消", onClick = cancelBatchMove)
                            }
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
                                AccountCard(
                                    account,
                                    showTotpOnCards,
                                    nowMillis,
                                    selected = account.id in batchSelectedIds,
                                    onClick = {
                                        if (batchSourceGroup != null) {
                                            batchSelectedIds = if (account.id in batchSelectedIds) batchSelectedIds - account.id else batchSelectedIds + account.id
                                        } else onPreviewAccountIdChange(account.id)
                                    },
                                    onLongClick = { if (batchSourceGroup == null) menuAccount = account },
                                    hasPasskey = account.id in passkeyAccountIds
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    previewAccountId?.let { id -> accounts.firstOrNull { it.id == id } }?.let { account ->
        AccountPreviewSheet(account, clipboardClearSeconds, { onPreviewAccountIdChange(null) }, { onPreviewAccountIdChange(null); onEditAccount(account.id) }, { onPreviewAccountIdChange(null); onOpenDetail(account.id) }, maskChar = maskChar, onHotpAdvance = { onHotpAdvance(account.id) })
    }
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
                if (previewAccountId == account.id) onPreviewAccountIdChange(null)
                deleteConfirmAccount = null
            },
            onDismiss = { deleteConfirmAccount = null }
        )
    }
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
                    cancelBatchMove()
                }, textColor = MaterialTheme.colorScheme.primary)
            },
            dismissButton = { TextActionButton("取消", onClick = { moveTargetGroup = null }) }
        )
    }
    deleteConfirmIds?.let { ids ->
        DeleteConfirmDialog(
            title = "删除账号",
            message = "确定删除已选 ${ids.size} 个账号吗？此操作不可撤销。",
            onConfirm = {
                onDeleteAccounts(ids)
                deleteConfirmIds = null
                cancelBatchMove()
            },
            onDismiss = { deleteConfirmIds = null }
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier)
                            .combinedClickable(onClick = { onGroupTap(group.id) }, onLongClick = { onGroupLongPress(group.id) })
                    ) {
                        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp))
                    }
                }
            }
        }
        HorizontalDivider()
        TextActionButton("⚙", onManageGroups, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.labelSmall)
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
    onLongClick: () -> Unit,
    hasPasskey: Boolean = false
) {
    SurfaceCard(modifier = Modifier
        .fillMaxWidth()
        .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val hotp = account.hasTotp && account.isHotp
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(account.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (hasPasskey) Text("🔑", style = MaterialTheme.typography.titleSmall)
                }
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

internal fun accountCopyableText(account: Account): String = buildList {
    add("名称：${account.name}")
    add("用户名：${account.username}")
    add("密码：${account.password}")
    account.customFields.forEach { add("${it.label}：${it.value}") }
}.joinToString("\n")

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE == "system") {
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
