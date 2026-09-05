package com.example.vocabwatch.ui.study

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 一叠卡片 + 手势。内容交给 front / back 两个插槽，这里只管叠卡视觉、
 * 翻面动画、拖动跟手、松手后的飞出与回弹。
 *
 * onSwipeRight / onSwipeUp 传 null 就代表当前不接受该方向手势——卡片还没
 * 翻面时不能评分，拼写卡靠点字母作答。
 */
@Composable
fun StudyCardDeck(
    cardKey: Any,
    behindCount: Int,
    flipped: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onSwipeRight: (() -> Unit)?,
    onSwipeUp: (() -> Unit)?,
    rightHint: String?,
    upHint: String?,
    modifier: Modifier = Modifier,
    onPullReveal: (() -> Unit)? = null,
    undoReplay: SwipeDirection? = null,
    front: @Composable ColumnScope.() -> Unit,
    back: (@Composable ColumnScope.() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember(cardKey) { Animatable(0f) }
    val offsetY = remember(cardKey) { Animatable(0f) }
    val flipAngle by animateFloatAsState(if (flipped) 180f else 0f, tween(280), label = "flip")
    val allowRight = onSwipeRight != null
    val allowUp = onSwipeUp != null

    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val thresholds = CardSwipeThresholds(
            commitX = with(density) { 60.dp.toPx() },
            commitY = with(density) { 52.dp.toPx() },
            revealY = with(density) { 26.dp.toPx() },
            exitX = with(density) { (maxWidth + 48.dp).toPx() },
            exitY = with(density) { (maxHeight + 48.dp).toPx() }
        )
        // 圆屏半径约 105dp，卡片对角不能超出，否则四个角被屏幕圆弧切掉
        val cardWidth = maxWidth * 0.82f
        val cardHeight = maxHeight * 0.66f

        // 撤销：卡片从当初飞出去的方向原路飞回来
        LaunchedEffect(cardKey) {
            when (undoReplay) {
                SwipeDirection.RIGHT -> {
                    offsetX.snapTo(thresholds.exitX)
                    offsetX.animateTo(0f, tween(260))
                }

                SwipeDirection.UP -> {
                    offsetY.snapTo(-thresholds.exitY)
                    offsetY.animateTo(0f, tween(260))
                }

                null -> Unit
            }
        }

        DeckBehind(behindCount, cardWidth, cardHeight)

        Box(
            Modifier
                .size(cardWidth, cardHeight)
                .graphicsLayer {
                    translationX = offsetX.value
                    translationY = offsetY.value
                    rotationZ = offsetX.value / 40f
                }
                .pointerInput(cardKey) { detectFlipAndUndo(onTap, onDoubleTap) }
                .pointerInput(cardKey, allowRight, allowUp, onPullReveal != null) {
                    detectCardSwipe(
                        scope = scope,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        thresholds = thresholds,
                        allowRight = allowRight,
                        allowUp = allowUp,
                        onPullReveal = onPullReveal,
                        onCommit = { direction ->
                            if (direction == SwipeDirection.RIGHT) onSwipeRight?.invoke() else onSwipeUp?.invoke()
                        }
                    )
                }
        ) {
            // 翻面旋转必须放在手势节点的内层：外层一旦旋转 180°，
            // 局部坐标 X 轴镜像，背面收到的拖拽增量就是反的
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = flipAngle }) {
                if (flipAngle <= 90f) {
                    CardFace(Modifier.fillMaxSize(), content = front)
                } else {
                    CardFace(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationY = 180f },
                        content = { back?.invoke(this) }
                    )
                }
            }
        }

        CardHintOverlay(
            rightHint = if (allowRight) rightHint else null,
            upHint = if (allowUp) upHint else null,
            rightProgress = { (offsetX.value / thresholds.commitX).coerceIn(0f, 1f) },
            upProgress = { (-offsetY.value / thresholds.commitY).coerceIn(0f, 1f) }
        )
    }
}
