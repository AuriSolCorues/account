/**
 * 职责：底部弹层（Bottom Sheet）的通用容器与条目——统一背景、内边距、导航栏避让；
 *       另有 SheetPagePreview 做 IDE 预览底座。
 * 架构位置：AccountActionSheet、AccountPreviewSheet、密码生成器等面板都装在 AppBottomSheet 里。
 * Python 类比：ModalBottomSheet ≈ 系统 modal 容器；skipPartiallyExpanded=true 跳过「半开」档位，
 *           弹出即到全高——内容不多的面板用不着先停在半屏。
 */
package com.copy.account.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.BuildConfig
import com.copy.account.ui.theme.AccountTheme

/**
 * 底部弹出的通用容器：所有面板共用同一背景、内边距和导航栏避让，
 * 只有内容不同。纵向间距由面板自行决定（传 Arrangement 或插 Spacer）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppBottomSheet(
    onDismiss: () -> Unit,
    skipPartiallyExpanded: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    ) {
        Column(
            // 内容可滚：长账号（多字段/长值）超弹层最大高时不被裁，可滚到底；短内容仍自适应高度。
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

/** 底部面板/弹窗标题行：左侧粗体标题 + 右侧可选的保存等动作。 */
@Composable
internal fun SheetTitleRow(title: String, action: @Composable RowScope.() -> Unit = {}) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        action()
    }
}

/**
 * IDE 预览底座：ModalBottomSheet 在预览窗不弹层，此件把面板内容画成「遮罩 + 底部圆角面板」，
 * Design/Split 视图即可按页面直查成品。运行时仍走 AppBottomSheet 真弹层。
 * verticalArrangement 须与对应面板 AppBottomSheet 调用一致，排版才相同。
 */
@Composable
internal fun SheetPagePreview(
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))) {
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

/** 底部面板中整行可点击的条目（同背景、同圆角，文本颜色区分主次）。 */
@Composable
internal fun ActionSheetRow(text: String, muted: Boolean = false, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            Text(text, color = if (color != Color.Unspecified) color else if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ActionSheetRowPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        ActionSheetRow("普通操作") {}
    }
}
