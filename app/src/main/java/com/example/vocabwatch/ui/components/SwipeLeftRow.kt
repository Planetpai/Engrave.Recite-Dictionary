package com.example.vocabwatch.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 左滑触发行内操作：历史记录左滑加错题集、错题集左滑移出。
 *
 * 不用 wear 的 SwipeToDismissBox——列表里要的是「滑过阈值触发一次动作」，
 * 不是整行被划掉。内容跟手左移，右侧提示随进度亮起，松手过阈值回调
 * [onSwipe]（通常父级会把这一行移掉），没过就弹回来。
 */
@Composable
fun SwipeLeftRow(
    onSwipe: () -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { 56.dp.toPx() }
    val offsetX = remember { Animatable(0f) }

    Box(modifier.fillMaxWidth()) {
        // 右侧对齐的提示，跟滑动进度变亮
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    alpha = ((-offsetX.value / threshold).coerceIn(0f, 1f) * 0.9f + 0.1f)
                },
            contentAlignment = Alignment.CenterEnd
        ) {
            androidx.wear.compose.material.Text(
                text = hint,
                style = androidx.wear.compose.material.MaterialTheme.typography.caption2,
                color = androidx.wear.compose.material.MaterialTheme.colors.onSurfaceVariant
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX.value }
                .pointerInput(threshold) {
                    detectDragGestures(
                        onDragStart = { },
                        onDrag = { change, amount ->
                            change.consume()
                            val next = (offsetX.value + amount.x).coerceIn(-threshold * 1.6f, 0f)
                            scope.launch { offsetX.snapTo(next) }
                        },
                        onDragEnd = {
                            if (offsetX.value <= -threshold) onSwipe()
                            scope.launch { offsetX.animateTo(0f, tween(200)) }
                        },
                        onDragCancel = {
                            scope.launch { offsetX.animateTo(0f, tween(200)) }
                        }
                    )
                }
        ) {
            content()
        }
    }
}
