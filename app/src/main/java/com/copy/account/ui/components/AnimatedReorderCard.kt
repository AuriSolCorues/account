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
    val currentOnMove by rememberUpdatedState(onMove)
    val dragModifier = if (onMove == null) Modifier else Modifier.reorderDragHandle(
        key = key,
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
