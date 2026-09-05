/**
 * 职责：长按账号弹出的操作面板（编辑/删除/模板新建/复制全部内容）。
 * 架构位置：HomeScreen 长按账号后经 AppBottomSheet 弹出；条目视觉复用 Sheet.kt 的 ActionSheetRow。
 * Python 类比：内容与容器分离（AccountActionContent 不含弹层）——≈ 把 HTML 片段
 *           与挂载它的 modal 分开写，同一段内容既给运行时也给 IDE 预览用。
 */
package com.copy.account.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.BuildConfig
import com.copy.account.data.model.Account
import com.copy.account.data.model.initialAccounts
import com.copy.account.ui.theme.AccountTheme

/** 长按账号的操作面板：编辑、删除、模板新建与复制全部内容。 */
@Composable
internal fun AccountActionSheet(
    account: Account,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTemplateNew: () -> Unit,
    onCopyAll: () -> Unit
) {
    AppBottomSheet(onDismiss = onDismiss, skipPartiallyExpanded = true, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AccountActionContent(account, onDismiss, onEdit, onDelete, onTemplateNew, onCopyAll)
    }
}

/** 操作面板内容（不含弹层容器），运行时与 IDE 预览共用。 */
@Composable
private fun AccountActionContent(
    account: Account,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTemplateNew: () -> Unit,
    onCopyAll: () -> Unit
) {
    Text(account.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextActionButton("编辑", onClick = { onDismiss(); onEdit() }, modifier = Modifier.weight(1f))
        DangerButton("删除", onClick = { onDismiss(); onDelete() }, modifier = Modifier.weight(1f))
    }
    ActionSheetRow("作为模板新建账号") { onDismiss(); onTemplateNew() }
    ActionSheetRow("复制账号全部内容") { onDismiss(); onCopyAll() }
    ActionSheetRow("取消", muted = true) { onDismiss() }
    Spacer(Modifier.height(8.dp))
}

@Preview(name = "长按账号操作面板", widthDp = 411, heightDp = 700, showBackground = true)
@Composable
private fun AccountActionSheetPagePreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        SheetPagePreview(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AccountActionContent(initialAccounts[1], onDismiss = {}, onEdit = {}, onDelete = {}, onTemplateNew = {}, onCopyAll = {})
        }
    }
}
