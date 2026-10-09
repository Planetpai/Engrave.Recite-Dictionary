package top.southfish.engrave.ui.study

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 一叠卡片 + 手势。内容交给 front / back 两个插槽，这里只管叠卡视觉、
 * 翻面动画、拖动跟手和松手后的飞出与回弹。
 *
 * 下层叠卡渲染 [behindFaces]（队列里下一个词的内容），当前卡被拖走时能
 * 直接看到接下来要背的词，而不是空白卡背。拖动时只有被按住的这张卡自己
 * 移动：横滑左右平移、上滑整卡上移，下层叠卡纹丝不动。
 *
 * 两个滑动回调传 null 就代表当前不接受该方向手势——卡片还没翻面时不能
 * 评分，拼写卡靠点字母作答。「已学过 / 会」是左滑，避开系统返回手势。
 *
 * 撤销手势（[onUndo]）放在卡片矩形外的留白环上：在卡片外面双击才撤回
 * 上一张卡，卡片自身的点按/滑动区域不响应撤销，避免翻面时误触。
 */
@Composable
fun StudyCardDeck(
    cardKey: Any,
    behindFaces: List<@Composable () -> Unit>,
    flipped: Boolean,
    onTap: () -> Unit,
    onUndo: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)?,
    onSwipeUp: (() -> Unit)?,
    modifier: Modifier = Modifier,
    undoReplay: SwipeDirection? = null,
    front: @Composable ColumnScope.() -> Unit,
    back: (@Composable ColumnScope.() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val dragging = remember { mutableStateOf(false) }
    val offsetX = remember(cardKey) { Animatable(0f) }
    val offsetY = remember(cardKey) { Animatable(0f) }
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
        val thresholds = CardSwipeThresholds(
            commitX = with(density) { 60.dp.toPx() },
            commitY = with(density) { 52.dp.toPx() },
            exitX = with(density) { (maxWidth + 48.dp).toPx() },
            exitY = with(density) { (maxHeight + 48.dp).toPx() }
        )
        val cardWidth = maxWidth * 0.88f
        val cardHeight = maxHeight * 0.78f

        // 卡片外的留白环：双击这里撤销。矩形按卡片在容器中的居中位置算，
        // 和下面卡片实际尺寸保持一致；落在卡片上的双击由检测器自己丢掉。
        if (onUndo != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(maxWidth, maxHeight) {
                        detectOutsideDoubleTap(
                            excludedRect = {
                                val cw = cardWidth.toPx()
                                val ch = cardHeight.toPx()
                                Rect(
                                    left = (size.width - cw) / 2f,
                                    top = (size.height - ch) / 2f,
                                    right = (size.width + cw) / 2f,
                                    bottom = (size.height + ch) / 2f
                                )
                            },
                            onDoubleTap = onUndo
                        )
                    }
            )
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
                    offsetY.snapTo(-thresholds.exitY)
                    offsetY.animateTo(0f, tween(260))
                }

                null -> Unit
            }
        }

        DeckBehind(behindFaces, cardWidth, cardHeight)

        Box(
            Modifier
                .size(cardWidth, cardHeight)
                .graphicsLayer {
                    translationX = offsetX.value
                    translationY = offsetY.value
                    rotationZ = offsetX.value / 40f
                }
                .pointerInput(cardKey, onLongPress) { detectTapAndLongPress(onTap, onLongPress) }
                .pointerInput(cardKey, allowLeft, allowUp) {
                    detectCardSwipe(
                        scope = scope,
                        dragging = dragging,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        thresholds = thresholds,
                        allowLeft = allowLeft,
                        allowRight = false,
                        allowUp = allowUp,
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
        }
    }
}
