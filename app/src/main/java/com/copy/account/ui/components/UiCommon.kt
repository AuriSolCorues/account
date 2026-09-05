package com.copy.account.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.copy.account.BuildConfig
import com.copy.account.security.copyToClipboard
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette
import kotlinx.coroutines.delay

@Composable
internal fun rememberClock(): Long {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }

    return nowMillis
}

@Composable
internal fun accountTopBarColors() = TopAppBarDefaults.topAppBarColors(
    // 每个页面使用完整的一套色板，切换主题时不混用明暗不同的顶栏和内容区。
    containerColor = LocalAccountThemePalette.current.topBar,
    titleContentColor = LocalAccountThemePalette.current.topBarText,
    navigationIconContentColor = LocalAccountThemePalette.current.topBarText,
    actionIconContentColor = LocalAccountThemePalette.current.topBarText
)

@Composable
internal fun SensitiveValueRow(label: String, value: String, masked: Boolean = false, sensitive: Boolean = masked, clearAfterSeconds: Int = 30, mask: Char = '•', afterCopy: (() -> Unit)? = null) {
    val context = LocalContext.current
    var revealed by remember(value, masked) { mutableStateOf(!masked) }
    val displayed = if (masked && !revealed) mask.toString().repeat(8) else value
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(76.dp))
        Text(
            displayed,
            modifier = Modifier.weight(1f).clickable {
                if (masked && !revealed) {
                    revealed = true
                } else {
                    copyToClipboard(context, value, sensitive, clearAfterSeconds)
                    afterCopy?.invoke()
                }
            },
            fontWeight = if (masked && !revealed) FontWeight.Normal else FontWeight.Medium
        )
        TextActionButton("复制", onClick = {
            copyToClipboard(context, value, sensitive, clearAfterSeconds)
            afterCopy?.invoke()
        })
    }
}

@Composable
internal fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** 长按拖动排序共用手势；每移动 48dp 通知一次方向。 */
internal fun Modifier.reorderDragHandle(
    key: Any,
    onMove: (Int) -> Unit,
    onDrag: (Float) -> Unit = {},
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {}
): Modifier = pointerInput(key) {
    var distance = 0f
    detectDragGesturesAfterLongPress(
        onDragStart = {
            distance = 0f
            onDragStart()
        },
        onDrag = { change, amount ->
            change.consume()
            onDrag(amount.y)
            distance += amount.y
            while (distance > 48f) {
                onMove(1)
                distance -= 48f
            }
            while (distance < -48f) {
                onMove(-1)
                distance += 48f
            }
        },
        onDragEnd = {
            distance = 0f
            onDragEnd()
        },
        onDragCancel = {
            distance = 0f
            onDragCancel()
        }
    )
}

/** 拖动排序手柄字形：拖动态时切换 ↕/☷ 及配色；与 reorderDragHandle/AnimatedReorderCard 配套。 */
@Composable
internal fun DragHandleGlyph(isDragging: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = if (isDragging) "↕" else "☷",
        color = if (isDragging) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
private fun SensitiveValueRowPreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        SensitiveValueRow("密码", "hunter2", masked = true, sensitive = true, clearAfterSeconds = 30)
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStatePreview() {
    AccountTheme(darkTheme = BuildConfig.DEFAULT_THEME_MODE != "light") {
        EmptyState("暂无账号", Modifier.fillMaxWidth())
    }
}
