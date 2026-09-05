package com.copy.account.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.copy.account.ui.theme.AccountTheme
import com.copy.account.ui.theme.LocalAccountThemePalette

internal data class ReorderCardStyle(
    val normalColor: Color,
    val draggingColor: Color,
    val normalElevation: Dp = 1.dp,
    val draggingElevation: Dp = 8.dp,
    val normalScale: Float = 1f,
    val draggingScale: Float = 1.03f,
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
    val dragModifier = if (onMove == null) Modifier else Modifier.reorderDragHandle(
        key = key,
        onMove = { direction ->
            onMove(direction)
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
        targetValue = if (isDragging) style.draggingElevation else style.normalElevation,
        label = "reorder-card-elevation"
    )
    val cardScale by animateFloatAsState(
        targetValue = if (isDragging) style.draggingScale else style.normalScale,
        label = "reorder-card-scale"
    )
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
                    androidx.compose.material3.Text("可复用卡片", modifier = Modifier.padding(padding))
                }
            }
        }
    }
}
