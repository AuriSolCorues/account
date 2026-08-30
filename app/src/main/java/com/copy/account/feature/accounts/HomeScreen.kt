package com.copy.account.feature.accounts

import android.annotation.SuppressLint
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copy.account.core.crypto.totpCode
import com.copy.account.core.security.copyToClipboard
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
    onManageGroups: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDetail: (String) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var previewAccount by remember { mutableStateOf<Account?>(null) }
    var menuAccount by remember { mutableStateOf<Account?>(null) }
    var deleteConfirmAccount by remember { mutableStateOf<Account?>(null) }
    var multiSelect by remember { mutableStateOf(false) }
    var multiSelectedIds by remember { mutableStateOf(emptySet<String>()) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(100)
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val selectedGroup = groups.firstOrNull { it.id == selectedGroupId } ?: groups.first()
    val showTotpOnCards = if (multiSelect) {
        multiSelectedIds.any { id -> groups.firstOrNull { it.id == id }?.kind == GroupKind.DYNAMIC }
    } else {
        selectedGroup.kind == GroupKind.DYNAMIC
    }
    val visibleAccounts = accounts.filter { account ->
        val inGroup = if (multiSelect) {
            multiSelectedIds.isEmpty() || multiSelectedIds.all { accountInGroup(account, groups, it) }
        } else {
            accountInGroup(account, groups, selectedGroup.id)
        }
        inGroup && accountMatchesSearch(account, groups, searchQuery)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = accountTopBarColors(),
                title = {
                    if (searchOpen) OutlinedTextField(searchQuery, { searchQuery = it }, placeholder = { Text("搜索账号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    else Text("账号本子", color = LocalAccountThemePalette.current.topBarText, fontWeight = FontWeight.Bold)
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
                    selectedGroupId = selectedGroupId,
                    multiSelect = multiSelect,
                    multiSelectedIds = multiSelectedIds,
                    onGroupTap = { id ->
                        if (multiSelect) {
                            multiSelectedIds = if (id in multiSelectedIds) multiSelectedIds - id else multiSelectedIds + id
                        } else onGroupSelected(id)
                    },
                    onGroupLongPress = { id ->
                        multiSelect = true
                        multiSelectedIds = multiSelectedIds + id
                    },
                    onManageGroups = onManageGroups,
                    modifier = Modifier.width(sidebarWidth).fillMaxHeight()
                )
                Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp)) {
                    if (multiSelect) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text("已选 ${multiSelectedIds.size} 个分组（交集）", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { multiSelect = false; multiSelectedIds = emptySet() }) { Text("完成") }
                        }
                    } else {
                        Text("${selectedGroup.name} · ${visibleAccounts.size} 条", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    if (visibleAccounts.isEmpty()) {
                        EmptyState(
                            when {
                                multiSelect -> "所选分组无交集账号"
                                selectedGroup.kind == GroupKind.DYNAMIC -> "在账号编辑页添加两步验证"
                                selectedGroup.kind == GroupKind.DEFAULT -> "暂无未分组账号"
                                else -> "暂无账号"
                            },
                            Modifier.fillMaxWidth().weight(1f)
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(visibleAccounts, key = { it.id }) { account ->
                                AccountCard(account, showTotpOnCards, nowMillis, onClick = { previewAccount = account }, onLongClick = { menuAccount = account })
                            }
                        }
                    }
                }
            }
        }
    }
    previewAccount?.let { account -> AccountPreviewSheet(account, clipboardClearSeconds, { previewAccount = null }, { previewAccount = null; onEditAccount(account.id) }, { previewAccount = null; onOpenDetail(account.id) }, maskChar = maskChar) }
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
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
internal fun GroupSidebar(
    groups: List<Group>,
    selectedGroupId: String,
    multiSelect: Boolean,
    multiSelectedIds: Set<String>,
    onGroupTap: (String) -> Unit,
    onGroupLongPress: (String) -> Unit,
    onManageGroups: () -> Unit,
    modifier: Modifier
) {
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                groups.forEach { group ->
                    val selected = if (multiSelect) group.id in multiSelectedIds else group.id == selectedGroupId
                    val selectionColor by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        label = "group-selection-color"
                    )
                    Surface(
                        color = selectionColor,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(if (selected) 1.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                            .pointerInput(group.id) {
                                detectTapGestures(
                                    onTap = { onGroupTap(group.id) },
                                    onLongPress = { onGroupLongPress(group.id) }
                                )
                            }
                    ) {
                        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp))
                    }
                }
            }
        }
        HorizontalDivider()
        TextButton(onClick = onManageGroups, modifier = Modifier.fillMaxWidth()) { Text("⚙ 分组管理", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
internal fun AccountCard(account: Account, showTotp: Boolean, nowMillis: Long, onClick: () -> Unit, onLongClick: () -> Unit) {
    SurfaceCard(modifier = Modifier
        .fillMaxWidth()
        .pointerInput(account.id) {
            detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() })
        }) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (showTotp && account.hasTotp) {
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
                Text(if (showTotp && account.hasTotp) "${totpCode(account, nowMillis)}  ·  ${account.totpPeriod - (nowMillis / 1000L % account.totpPeriod)} 秒" else account.username, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** 搜索跨账号名称、用户名、自定义字段与所属分组名匹配。 */
internal fun accountMatchesSearch(account: Account, groups: List<Group>, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    return account.name.contains(q, true) ||
        account.username.contains(q, true) ||
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
            onManageGroups = {},
            onOpenSettings = {},
            onOpenDetail = {}
        )
    }
}
