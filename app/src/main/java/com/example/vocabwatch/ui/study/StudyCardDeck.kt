package com.example.vocabwatch.ui.study

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 停驻时卡片缩到多小，和上移距离、例句区高度配套 */
private const val PARK_SCALE = 0.72f

/** 停驻时例句区的可用宽度，圆屏下半部分两侧会被弧切掉 */
private val REVEAL_WIDTH = 150.dp

/**
 * 一叠卡片 + 手势。内容交给 front / back 两个插槽，这里只管叠卡视觉、
 * 翻面动画、拖动跟手、停驻露例句和松手后的飞出与回弹。
 *
 * 下层叠卡渲染 [behindFaces]（队列里下一个词的内容），上拉时能直接看到
 * 接下来要背的词，而不是空白卡背。
 *
 * 三个滑动回调传 null 就代表当前不接受该方向手势——卡片还没翻面时不能
 * 评分，拼写卡靠点字母作答。「已学过 / 会」是左滑，避开系统返回手势。
 */
@Composable
fun StudyCardDeck(
    cardKey: Any,
    behindFaces: List<@Composable () -> Unit>,
    flipped: Boolean,
    parked: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)?,
    onSwipeUp: (() -> Unit)?,
    leftHint: String?,
    upHint: String?,
    modifier: Modifier = Modifier,
    onRevealOpen: (() -> Unit)? = null,
    onRevealClose: (() -> Unit)? = null,
    undoReplay: SwipeDirection? = null,
    revealContent: (@Composable () -> Unit)? = null,
    front: @Composable ColumnScope.() -> Unit,
    back: (@Composable ColumnScope.() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val dragging = remember { mutableStateOf(false) }
    val offsetX = remember(cardKey) { Animatable(0f) }
    val pull = remember(cardKey) { Animatable(0f) }
    // 翻面角度跟卡片走：新卡一进来就从它自己的角度开始（没翻过就是 0°），
    // 不会把上一张卡翻回正面的动画播到下一张卡头上
    val flipAngle = remember(cardKey) { Animatable(if (flipped) 180f else 0f) }
    LaunchedEffect(cardKey, flipped) {
        flipAngle.animateTo(if (flipped) 180f else 0f, tween(280))
    }
    val allowLeft = onSwipeLeft != null
    val allowUp = onSwipeUp != null

    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val parkPx = with(density) { 40.dp.toPx() }
        val thresholds = CardSwipeThresholds(
            commitX = with(density) { 60.dp.toPx() },
            commitY = with(density) { 52.dp.toPx() },
            revealY = with(density) { 26.dp.toPx() },
            closeY = with(density) { 30.dp.toPx() },
            parkPx = parkPx,
            exitX = with(density) { (maxWidth + 48.dp).toPx() },
            exitY = with(density) { (maxHeight + 48.dp).toPx() }
        )
        val cardWidth = maxWidth * 0.82f
        val cardHeight = maxHeight * 0.66f

        // 点按或撤销引起的停驻状态变化也要带动卡片升降；拖拽期间交给手势自己跟手
        LaunchedEffect(cardKey, parked) {
            if (!dragging.value) pull.animateTo(if (parked) 1f else 0f, tween(220))
        }

        // 撤销：卡片从当初飞出去的方向原路飞回来
        LaunchedEffect(cardKey) {
            when (undoReplay) {
                SwipeDirection.LEFT -> {
                    offsetX.snapTo(-thresholds.exitX)
                    offsetX.animateTo(0f, tween(260))
                }

                SwipeDirection.RIGHT -> {
                    offsetX.snapTo(thresholds.exitX)
                    offsetX.animateTo(0f, tween(260))
                }

                SwipeDirection.UP -> {
                    pull.snapTo(2.5f)
                    pull.animateTo(if (parked) 1f else 0f, tween(260))
                }

                null -> Unit
            }
        }

        DeckBehind(behindFaces, cardWidth, cardHeight) { pull.value.coerceIn(0f, 1f) }

        if (revealContent != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(bottom = 12.dp)
                    .graphicsLayer { alpha = pull.value.coerceIn(0f, 1f) },
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(Modifier.width(REVEAL_WIDTH)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(RevealDividerColor)
                    )
                    revealContent()
                }
            }
        }

        Box(
            Modifier
                .size(cardWidth, cardHeight)
                .graphicsLayer {
                    translationX = offsetX.value
                    translationY = -pull.value * parkPx
                    val s = 1f - (1f - PARK_SCALE) * pull.value.coerceIn(0f, 1f)
                    scaleX = s
                    scaleY = s
                    rotationZ = offsetX.value / 40f
                }
                .pointerInput(cardKey, onLongPress) { detectFlipAndUndo(onTap, onDoubleTap, onLongPress) }
                .pointerInput(cardKey, allowLeft, allowUp, onRevealOpen != null) {
                    detectCardSwipe(
                        scope = scope,
                        dragging = dragging,
                        offsetX = offsetX,
                        pull = pull,
                        thresholds = thresholds,
                        parked = parked,
                        allowLeft = allowLeft,
                        allowRight = false,
                        allowUp = allowUp,
                        onRevealOpen = onRevealOpen,
                        onRevealClose = onRevealClose,
                        onSettle = { target ->
                            scope.launch { pull.animateTo(if (target) 1f else 0f, tween(220)) }
                        },
                        onCommit = { direction ->
                            if (direction == SwipeDirection.LEFT) onSwipeLeft?.invoke() else onSwipeUp?.invoke()
                        }
                    )
                }
        ) {
            // 翻面旋转必须放在手势节点的内层：外层一旦旋转 180°，
            // 局部坐标 X 轴镜像，背面收到的拖拽增量就是反的
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = flipAngle.value }) {
                if (flipAngle.value <= 90f) {
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

            CardHintOverlay(
                leftHint = if (allowLeft) leftHint else null,
                upHint = if (allowUp) upHint else null,
                leftProgress = { (-offsetX.value / thresholds.commitX).coerceIn(0f, 1f) },
                upProgress = { (-pull.value * parkPx / thresholds.commitY).coerceIn(0f, 1f) }
            )
        }
    }
}
