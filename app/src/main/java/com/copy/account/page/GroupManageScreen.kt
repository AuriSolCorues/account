package com.copy.account.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.copy.account.data.model.FIXED_GROUP_COUNT
import com.copy.account.data.model.Group
import com.copy.account.data.model.GroupKind
import com.copy.account.data.model.initialGroups
import androidx.compose.ui.res.stringResource
import com.copy.account.R
import com.copy.account.ui.components.AppListScreen
import com.copy.account.ui.components.AppBottomSheet
import com.copy.account.ui.components.AnimatedReorderCard
import com.copy.account.ui.components.DangerButton
import com.copy.account.ui.components.DeleteConfirmDialog
import com.copy.account.ui.components.DragHandleGlyph
import com.copy.account.ui.components.TextInputDialog
import com.copy.account.ui.components.SheetTitleRow
import com.copy.account.ui.components.TextActionButton
import com.copy.account.ui.components.TopBarTextButton
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
    AppListScreen(stringResource(R.string.group_title), onBack, actions = { TopBarTextButton(stringResource(R.string.group_action_add), onClick = { showAddSheet = true; dialogText = "" }) }) {
        item { Text(stringResource(R.string.group_fixed_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(groups.take(FIXED_GROUP_COUNT), key = { it.id }) { group ->
            GroupManageItem(
                group = group,
                count = accountCount(group.id),
                onMove = null,
                onNameClick = { editGroup = group; dialogText = group.name }
            )
        }
        item { Text(stringResource(R.string.group_custom_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp)) }
        items(groups.drop(FIXED_GROUP_COUNT), key = { it.id }) { group ->
            GroupManageItem(
                group = group,
                count = accountCount(group.id),
                onMove = { onMoveCustomGroup(group.id, it) },
                onNameClick = { editGroup = group; dialogText = group.name }
            )
        }
        item { Text(stringResource(R.string.group_footer_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
    }
    if (showAddSheet) {
        TextInputDialog(
            title = stringResource(R.string.group_add_title),
            label = stringResource(R.string.group_name_label),
            onConfirm = { name ->
                onAddGroup(name.trim())
                showAddSheet = false
            },
            onDismiss = { showAddSheet = false }
        )
    }
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
            title = stringResource(R.string.group_delete_title),
            message = stringResource(R.string.group_delete_message, group.name, accountCount(group.id)),
            onConfirm = { onDeleteGroup(group.id); deleteConfirmGroup = null },
            onDismiss = { deleteConfirmGroup = null }
        )
    }
}

@Composable
internal fun LazyItemScope.GroupManageItem(
    group: Group,
    count: Int,
    onMove: ((Int) -> Unit)?,
    onNameClick: () -> Unit
) {
    AnimatedReorderCard(
        key = group.id,
        style = ReorderCardStyle(
            normalColor = MaterialTheme.colorScheme.surfaceContainer,
            draggingColor = MaterialTheme.colorScheme.primaryContainer,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ),
        onMove = onMove
    ) { dragModifier, isDragging, paddingValues ->
        Row(modifier = Modifier.fillMaxWidth().padding(paddingValues), verticalAlignment = Alignment.CenterVertically) {
            Text(group.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).clickable(onClick = onNameClick), maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (isDragging) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (onMove != null) DragHandleGlyph(isDragging = isDragging, modifier = dragModifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun RenameGroupSheetContent(
    group: Group,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    SheetTitleRow(stringResource(R.string.group_edit_title)) {
        TextActionButton(
            text = stringResource(R.string.action_save),
            enabled = value.isNotBlank(),
            onClick = onSave
        )
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.group_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    if (group.kind == GroupKind.CUSTOM) {
        DangerButton(stringResource(R.string.group_delete_action), onClick = onDelete)
    }
    Spacer(Modifier.height(8.dp))
}

@Preview(name = "编辑分组面板", widthDp = 411, heightDp = 600, showBackground = true)
@Composable
private fun RenameGroupSheetPagePreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE == "system") {
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
    val previewContext = androidx.compose.ui.platform.LocalContext.current
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE == "system") {
        GroupManageScreen(
            groups = initialGroups(previewContext),
            onBack = {},
            onAddGroup = { "custom-preview" },
            onRenameGroup = { _, _ -> },
            onDeleteGroup = {},
            accountCount = { 2 },
            onMoveCustomGroup = { _, _ -> }
        )
    }
}
