/**
 * 职责：拖动排序动效全家——长按拖动手势（reorderDragHandle，48dp 步进）、手柄字形
 *       （DragHandleGlyph）、可复用拖曳卡片（AnimatedReorderCard：颜色/高度/缩放过渡
 *       动画、拖动视觉位移、拖起置顶）。三层配套，改拖动排序的手感只开本文件。
 * 架构位置：GroupManageScreen 分组排序、AccountEditScreen 自定义字段行排序使用
 *           （首页批量拖动已随自定义排序功能拆除）。
 * Python 类比：graphicsLayer 的 translationY ≈ CSS transform: translateY——只动「画」不动
 *           「布局」；真实列表位置由 onMove 换列表项达成，两套动画各走各的、互不拉扯。
 */
package com.copy.account.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette

internal data class ReorderCardStyle(
    val normalColor: Color,
    val draggingColor: Color,
    val contentPadding: PaddingValues = PaddingValues()
)

/** 可复用拖曳卡片：页面只传样式、移动回调与内容。 */
@Composable
internal fun LazyItemScope.AnimatedReorderCard(
    key: Any,
    style: ReorderCardStyle,
    onMove: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (dragModifier: Modifier, isDragging: Boolean, PaddingValues) -> Unit
) {
    var dragOffsetY by remember(key) { mutableStateOf(0f) }
    var isDragging by remember(key) { mutableStateOf(false) }
    // onMove 经 rememberUpdatedState 转发：pointerInput(key) 在拖动中不重启，直捕快照的
    // 旧闭包会在列表换位后算出错误索引（真机复现：拖一张、好几张乱动的根因）；
    // 经 State 间接读，每次 48dp 步进都拿到页面重组后的最新回调。
    val currentOnMove by rememberUpdatedState(onMove)
    val dragModifier = if (onMove == null) Modifier else Modifier.reorderDragHandle(
        key = key,
        // 换位后卡片会瞬间「跳」到新槽位；dragOffsetY 反向抵消这一跳（56f≈行高），
        // 让指尖下的卡片视觉连续，松手归零即自然落位。
        onMove = { direction ->
            currentOnMove?.invoke(direction)
            dragOffsetY -= direction * 56f
        },
        onDrag = { amount -> dragOffsetY += amount },
        onDragStart = { isDragging = true; dragOffsetY = 0f },
        onDragEnd = { dragOffsetY = 0f; isDragging = false },
        onDragCancel = { dragOffsetY = 0f; isDragging = false }
    )
    val cardColor by animateColorAsState(
        targetValue = if (isDragging) style.draggingColor else style.normalColor,
        label = "reorder-card-color"
    )
    val cardElevation by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 1.dp,
        label = "reorder-card-elevation"
    )
    val cardScale by animateFloatAsState(
        targetValue = if (isDragging) 1.03f else 1f,
        label = "reorder-card-scale"
    )
    // animateItem()：列表项换位时的槽位平移动画；zIndex 拖起时浮到最顶层，盖住邻卡。
    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
        modifier = modifier
            .fillMaxWidth()
            .animateItem()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffsetY
                scaleX = cardScale
                scaleY = cardScale
            }
    ) {
        content(dragModifier, isDragging, style.contentPadding)
    }
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
    // pointerInput(key)：key 变化即整体重启手势识别器（这里 key=行数据，内容变了手势重挂）。
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
            // 48dp 为触发步长：累计拖过量即折算成「移动一行」事件，余数留给下一段拖动。
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
private fun AnimatedReorderCardPreview() {
    AccountTheme {
        androidx.compose.foundation.lazy.LazyColumn {
            item {
                AnimatedReorderCard(
                    key = "preview",
                    style = ReorderCardStyle(
                        normalColor = LocalAccountThemePalette.current.surface,
                        draggingColor = LocalAccountThemePalette.current.selectedBackground,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 3.dp)
                    ),
                    onMove = {}
                ) { _, _, padding ->
                    Text("可复用卡片", modifier = Modifier.padding(padding))
                }
            }
        }
    }
}
