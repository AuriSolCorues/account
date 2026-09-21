package com.copy.account.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.ui.components.ActionSheetRow
import com.copy.account.ui.components.SheetPagePreview
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette

private const val PopupOverrideTheme = """{
  "version": 1,
  "name": "弹层覆盖预览",
  "defaultMode": "dark",
  "colors": {
    "popupSurface": "#294A73",
    "popupItemSurface": "#202A38"
  }
}"""

private enum class PopupPreviewState { Sheet, Dialog }

@Preview(name = "弹层继承 · 底部面板", widthDp = 411, heightDp = 760, showBackground = true)
@Composable
private fun InheritedSheetPreview() {
    AccountTheme(darkTheme = true) { PopupThemeSimulator(PopupPreviewState.Sheet) }
}

@Preview(name = "弹层覆盖 · 底部面板", widthDp = 411, heightDp = 760, showBackground = true)
@Composable
private fun OverriddenSheetPreview() {
    AccountTheme(darkTheme = true, customThemeJson = PopupOverrideTheme) { PopupThemeSimulator(PopupPreviewState.Sheet) }
}

@Preview(name = "弹层覆盖 · 确认窗口", widthDp = 411, heightDp = 760, showBackground = true)
@Composable
private fun OverriddenDialogPreview() {
    AccountTheme(darkTheme = true, customThemeJson = PopupOverrideTheme) { PopupThemeSimulator(PopupPreviewState.Dialog) }
}

@Composable
private fun PopupThemeSimulator(state: PopupPreviewState) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("账号本子", style = MaterialTheme.typography.headlineSmall)
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("示例账号", style = MaterialTheme.typography.titleMedium)
                    Text("user@example.com", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        when (state) {
            PopupPreviewState.Sheet -> SheetPagePreview(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("示例账号", style = MaterialTheme.typography.titleLarge)
                ActionSheetRow("编辑") {}
                ActionSheetRow("删除", color = MaterialTheme.colorScheme.error) {}
                ActionSheetRow("取消", muted = true) {}
            }
            PopupPreviewState.Dialog -> {
                Box(modifier = Modifier.fillMaxSize().background(LocalAccountThemePalette.current.overlay), contentAlignment = Alignment.Center) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp), modifier = Modifier.padding(28.dp)) {
                        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("删除账号", style = MaterialTheme.typography.titleLarge)
                            Text("确认删除「示例账号」吗？", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("取消    删除", color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.End))
                        }
                    }
                }
            }
        }
    }
}
