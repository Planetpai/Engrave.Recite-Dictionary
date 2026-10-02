package top.southfish.engrave.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
 *
 * 必须用水平轴检测器（detectHorizontalDragGestures）而不是任意方向的
 * detectDragGestures：后者只要过了 touch slop 就把整串手势消费掉，列表
 * 上下滚动会被行内拖拽劫持（手指稍有抖动就没法滚）。水平检测器只在
 * 横向位移过 slop 时才接管，纯竖滑/竖向为主的抖动会原样交给父级列表。
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
            androidx.compose.material3.Text(
                text = hint,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX.value }
                .pointerInput(threshold) {
                    detectHorizontalDragGestures(
                        onDragStart = { },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            val next = (offsetX.value + amount).coerceIn(-threshold * 1.6f, 0f)
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
