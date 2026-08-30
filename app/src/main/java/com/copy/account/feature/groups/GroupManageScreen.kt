package com.copy.account.feature.groups

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.copy.account.data.model.Group
import com.copy.account.data.model.GroupKind
import com.copy.account.ui.components.accountTopBarColors
import com.copy.account.ui.theme.LocalAccountThemePalette

@OptIn(ExperimentalMaterial3Api::class)
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
    var dialogGroup by remember { mutableStateOf<Group?>(null) }
    var actionGroup by remember { mutableStateOf<Group?>(null) }
    var deleteConfirmGroup by remember { mutableStateOf<Group?>(null) }
    var dialogText by remember { mutableStateOf("") }
    var showAddSheet by remember { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(colors = accountTopBarColors(), title = { Text("分组管理", color = LocalAccountThemePalette.current.topBarText) }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回", color = LocalAccountThemePalette.current.topBarText) } }, actions = { TextButton(onClick = { showAddSheet = true; dialogText = "" }) { Text("＋ 新增", color = LocalAccountThemePalette.current.topBarText) } })
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("固定分组（可改名）", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
            items(groups.take(2), key = { it.id }) { group ->
                GroupManageItem(
                    group = group,
                    count = accountCount(group.id),
                    fixed = true,
                    moveUp = {},
                    moveDown = {},
                    rename = { dialogGroup = group; dialogText = group.name },
                    delete = {},
                    onNameClick = { actionGroup = group }
                )
            }
            item { Text("自定义分组（长按拖动排序）", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp)) }
            items(groups.drop(2), key = { it.id }) { group ->
                val index = groups.drop(2).indexOf(group)
                GroupManageItem(
                    group = group,
                    count = accountCount(group.id),
                    fixed = false,
                    moveUp = { onMoveCustomGroup(group.id, -1) },
                    moveDown = { onMoveCustomGroup(group.id, 1) },
                    rename = { dialogGroup = group; dialogText = group.name },
                    delete = { deleteConfirmGroup = group },
                    first = index == 0,
                    last = index == groups.drop(2).lastIndex,
                    onNameClick = { actionGroup = group }
                )
            }
            item { Text("固定分组不可删除或排序；删除自定义分组不会删除账号。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
        }
    }
    // 新增沿用参考应用的底部面板转场；改名仍保留当前项目的对话框流程。
    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Text("新增分组", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = dialogText,
                    onValueChange = { dialogText = it },
                    label = { Text("分组名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showAddSheet = false }) { Text("取消") }
                    TextButton(
                        enabled = dialogText.isNotBlank(),
                        onClick = {
                            onAddGroup(dialogText.trim())
                            showAddSheet = false
                        }
                    ) { Text("保存") }
                }
            }
        }
    }
    // 点按标签先显示操作面板；选择“修改”后仍进入项目原有的改名对话框。
    actionGroup?.let { group ->
        ModalBottomSheet(
            onDismissRequest = { actionGroup = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().clickable {
                        actionGroup = null
                        dialogGroup = group
                        dialogText = group.name
                    }
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        Text("修改分组名称")
                    }
                }
                if (group.kind == GroupKind.CUSTOM) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().clickable {
                            actionGroup = null
                            deleteConfirmGroup = group
                        }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                            Text("删除此分组", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().clickable { actionGroup = null }
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    dialogGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { dialogGroup = null },
            title = { Text("修改分组名称") },
            text = { OutlinedTextField(dialogText, { dialogText = it }, label = { Text("分组名称") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = dialogText.isNotBlank(), onClick = {
                    onRenameGroup(group.id, dialogText.trim())
                    dialogGroup = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { dialogGroup = null }) { Text("取消") } }
        )
    }
    deleteConfirmGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { deleteConfirmGroup = null },
            title = { Text("删除分组") },
            text = { Text("删除「${group.name}」将解除 ${accountCount(group.id)} 个账号的关联，账号本身不会被删除。") },
            confirmButton = {
                TextButton(onClick = { onDeleteGroup(group.id); deleteConfirmGroup = null }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmGroup = null }) { Text("取消") } }
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
    rename: () -> Unit,
    delete: () -> Unit,
    first: Boolean = false,
    last: Boolean = false,
    onNameClick: () -> Unit = {}
) {
    var dragDistance by remember(group.id) { mutableStateOf(0f) }
    var dragOffsetY by remember(group.id) { mutableStateOf(0f) }
    var isDragging by remember(group.id) { mutableStateOf(false) }
    val dragModifier = if (fixed) Modifier else Modifier.pointerInput(group.id) {
        detectDragGesturesAfterLongPress(
            onDragStart = { isDragging = true; dragOffsetY = 0f; dragDistance = 0f },
            onDrag = { change, amount ->
                change.consume()
                dragOffsetY += amount.y
                dragDistance += amount.y
                if (dragDistance > 48f) { moveDown(); dragDistance -= 48f; dragOffsetY -= 56f }
                if (dragDistance < -48f) { moveUp(); dragDistance += 48f; dragOffsetY += 56f }
            },
            onDragEnd = { dragDistance = 0f; dragOffsetY = 0f; isDragging = false },
            onDragCancel = { dragDistance = 0f; dragOffsetY = 0f; isDragging = false }
        )
    }
    val cardColor by animateColorAsState(
        targetValue = if (isDragging) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "group-drag-color"
    )
    val cardElevation by animateDpAsState(if (isDragging) 8.dp else 1.dp, label = "group-drag-elevation")
    val cardScale by animateFloatAsState(if (isDragging) 1.03f else 1f, label = "group-drag-scale")
    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
        modifier = Modifier
            .fillMaxWidth()
            // 列表重新排序时让邻项平滑让位，删除/新增也使用同一套转场。
            .animateItem()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffsetY
                scaleX = cardScale
                scaleY = cardScale
            }
            .then(dragModifier)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!fixed) Text(if (isDragging) "↕" else "☷", color = if (isDragging) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
            Text(group.name, modifier = Modifier.weight(1f).clickable(onClick = onNameClick), maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (isDragging) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = rename) { Text("改名") }
            if (!fixed) TextButton(onClick = delete) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
}
