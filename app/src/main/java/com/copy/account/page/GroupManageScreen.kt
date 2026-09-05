package com.copy.account.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.data.model.Group
import com.copy.account.data.model.GroupKind
import com.copy.account.data.model.initialGroups
import com.copy.account.ui.components.AppBottomSheet
import com.copy.account.ui.components.AnimatedReorderCard
import com.copy.account.ui.components.AppScreen
import com.copy.account.ui.components.DangerButton
import com.copy.account.ui.components.DeleteConfirmDialog
import com.copy.account.ui.components.DragHandleGlyph
import com.copy.account.ui.components.SheetTitleRow
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.components.ReorderCardStyle
import com.copy.account.ui.components.SheetPagePreview
import com.copy.account.BuildConfig
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette

@Composable
internal fun GroupManageScreen(
    groups: List<Group>,
    onBack: () -> Unit,
    onAddGroup: (String) -> String,
    onRenameGroup: (String, String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    accountCount: (String) -> Int,
    onMoveCustomGroup: (String, Int) -> Unit
) {
    var editGroup by remember { mutableStateOf<Group?>(null) }
    var deleteConfirmGroup by remember { mutableStateOf<Group?>(null) }
    var dialogText by remember { mutableStateOf("") }
    var showAddSheet by remember { mutableStateOf(false) }
    AppScreen(title = "分组管理", onBack = onBack, actions = { TextActionButton("＋ 新增", onClick = { showAddSheet = true; dialogText = "" }, textColor = LocalAccountThemePalette.current.topBarText) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("固定分组（可改名）", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
            items(groups.take(2), key = { it.id }) { group ->
                GroupManageItem(
                    group = group,
                    count = accountCount(group.id),
                    fixed = true,
                    moveUp = {},
                    moveDown = {},
                    onNameClick = { editGroup = group; dialogText = group.name }
                )
            }
            item { Text("自定义分组（长按拖动排序）", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp)) }
            items(groups.drop(2), key = { it.id }) { group ->
                GroupManageItem(
                    group = group,
                    count = accountCount(group.id),
                    fixed = false,
                    moveUp = { onMoveCustomGroup(group.id, -1) },
                    moveDown = { onMoveCustomGroup(group.id, 1) },
                    onNameClick = { editGroup = group; dialogText = group.name }
                )
            }
            item { Text("固定分组不可删除或排序；删除自定义分组不会删除账号。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
        }
    }
    // 新增和编辑均使用底部面板；编辑分组直接保存，不经过额外操作菜单。
    if (showAddSheet) {
        AppBottomSheet(onDismiss = { showAddSheet = false }, skipPartiallyExpanded = true) {
            AddGroupSheetContent(
                value = dialogText,
                onValueChange = { dialogText = it },
                onCancel = { showAddSheet = false },
                onSave = {
                    onAddGroup(dialogText.trim())
                    showAddSheet = false
                }
            )
        }
    }
    // 点名称直接进入编辑框，不再经过操作菜单或展示行内改名/删除按钮。
    editGroup?.let { group ->
        AppBottomSheet(onDismiss = { editGroup = null }, skipPartiallyExpanded = true, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RenameGroupSheetContent(
                group = group,
                value = dialogText,
                onValueChange = { dialogText = it },
                onSave = {
                    onRenameGroup(group.id, dialogText.trim())
                    editGroup = null
                },
                onDelete = { editGroup = null; deleteConfirmGroup = group }
            )
        }
    }
    deleteConfirmGroup?.let { group ->
        DeleteConfirmDialog(
            title = "删除分组",
            message = "删除「${group.name}」将解除 ${accountCount(group.id)} 个账号的关联，账号本身不会被删除。",
            onConfirm = { onDeleteGroup(group.id); deleteConfirmGroup = null },
            onDismiss = { deleteConfirmGroup = null }
        )
    }
}

@Composable
internal fun LazyItemScope.GroupManageItem(
    group: Group,
    count: Int,
    fixed: Boolean,
    moveUp: () -> Unit,
    moveDown: () -> Unit,
    onNameClick: () -> Unit
) {
    AnimatedReorderCard(
        key = group.id,
        style = ReorderCardStyle(
            normalColor = MaterialTheme.colorScheme.surfaceContainer,
            draggingColor = MaterialTheme.colorScheme.primaryContainer,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ),
        onMove = if (fixed) null else { direction -> if (direction > 0) moveDown() else moveUp() }
    ) { dragModifier, isDragging, paddingValues ->
        Row(modifier = Modifier.fillMaxWidth().padding(paddingValues), verticalAlignment = Alignment.CenterVertically) {
            Text(group.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).clickable(onClick = onNameClick), maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (isDragging) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (!fixed) DragHandleGlyph(isDragging = isDragging, modifier = dragModifier.padding(start = 12.dp))
        }
    }
}

/** 新增分组面板内容（不含弹层容器），运行时与 IDE 预览共用。 */
@Composable
private fun AddGroupSheetContent(
    value: String,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    SheetTitleRow("新增分组")
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("分组名称") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.End
    ) {
        TextActionButton("取消", onClick = onCancel)
        TextActionButton(
            text = "保存",
            enabled = value.isNotBlank(),
            onClick = onSave
        )
    }
}

/** 编辑分组面板内容（不含弹层容器），运行时与 IDE 预览共用。 */
@Composable
private fun RenameGroupSheetContent(
    group: Group,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    SheetTitleRow("编辑分组") {
        TextActionButton(
            text = "保存",
            enabled = value.isNotBlank(),
            onClick = onSave
        )
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("分组名称") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    if (group.kind == GroupKind.CUSTOM) {
        DangerButton("删除分组", onClick = onDelete)
    }
    Spacer(Modifier.height(8.dp))
}

@Preview(name = "新增分组面板", widthDp = 411, heightDp = 600, showBackground = true)
@Composable
private fun AddGroupSheetPagePreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        SheetPagePreview {
            AddGroupSheetContent(value = "旅行", onValueChange = {}, onCancel = {}, onSave = {})
        }
    }
}

@Preview(name = "编辑分组面板", widthDp = 411, heightDp = 600, showBackground = true)
@Composable
private fun RenameGroupSheetPagePreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        SheetPagePreview(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RenameGroupSheetContent(
                group = Group(id = "custom-preview", name = "旅行", kind = GroupKind.CUSTOM),
                value = "旅行",
                onValueChange = {},
                onSave = {},
                onDelete = {}
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun GroupManageScreenPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        GroupManageScreen(
            groups = initialGroups,
            onBack = {},
            onAddGroup = { "custom-preview" },
            onRenameGroup = { _, _ -> },
            onDeleteGroup = {},
            accountCount = { 2 },
            onMoveCustomGroup = { _, _ -> }
        )
    }
}
