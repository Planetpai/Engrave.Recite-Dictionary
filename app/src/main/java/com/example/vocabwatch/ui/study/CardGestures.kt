package com.example.vocabwatch.ui.study

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.MutableState
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** 卡片手势的距离阈值，单位 px，由调用方按屏幕密度换算好。 */
internal class CardSwipeThresholds(
    /** 横向拖过这个距离松手就算左滑 / 右滑成立 */
    val commitX: Float,
    /** 从手势起点上拉过这个距离松手就算「不会 / 不认识」 */
    val commitY: Float,
    /** 浏览阶段上拉多少开始停驻露例句 */
    val revealY: Float,
    /** 停驻后下拉多少收起例句 */
    val closeY: Float,
    /** pull = 1 时卡片上移的像素，也是例句区的高度 */
    val parkPx: Float,
    /** 卡片飞出去的目标位移 */
    val exitX: Float,
    val exitY: Float
)

/**
 * 点击翻面 + 双击撤销。
 *
 * 不用 detectTapGestures：它设了 onDoubleTap 之后，单击要等双击超时才回调，
 * 手表上翻面会明显拖一下。这里第一下抬手就立刻翻面，再等一个双击窗口，
 * 等到了就撤销——翻面紧跟着被撤销覆盖，观感正是「卡片飞回来」。
 */
internal suspend fun PointerInputScope.detectFlipAndUndo(
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown()
        val downTime = down.uptimeMillis
        val up = waitForUpOrCancellation() ?: return@awaitEachGesture // 拖走了，交给拖拽检测

        // 长按（≥系统长按时长）不当作单击：长按动作由独立的
        // detectTapGestures(onLongPress) 在按住期间触发，这里直接吞掉
        val isLongPress = up.uptimeMillis - downTime >= longPressTimeout
        if (isLongPress && onLongPress != null) {
            // 长按的响应由长按检测器负责，这里不触发单击
            return@awaitEachGesture
        }

        onTap()
        val second = withTimeoutOrNull(doubleTapTimeout) {
            awaitFirstDown()
            waitForUpOrCancellation()
        }
        if (second != null) onDoubleTap()
    }
}

/**
 * 拖动跟手 + 松手后飞出或回弹。
 *
 * 横向：[allowLeft] / [allowRight] 决定哪个方向能拖动，「已学过 / 会」走左滑，
 * 特意避开 Wear 从左缘右滑返回的系统手势。
 *
 * 纵向用 [pull]（0..1）描述例句展开程度：未停驻时上拉跟手，越过 [revealY]
 * 回调 [onRevealOpen] 让卡片停到上方；停驻后下拉越过 [closeY] 回调
 * [onRevealClose] 复位。两个方向继续上拉过 [commitY] 都算「不会」。
 * 所有阈值都相对手势起点，拖到一半停驻状态翻转也不会跳变。
 */
internal suspend fun PointerInputScope.detectCardSwipe(
    scope: CoroutineScope,
    dragging: MutableState<Boolean>,
    offsetX: Animatable<Float, AnimationVector1D>,
    pull: Animatable<Float, AnimationVector1D>,
    thresholds: CardSwipeThresholds,
    parked: Boolean,
    allowLeft: Boolean,
    allowRight: Boolean,
    allowUp: Boolean,
    onRevealOpen: (() -> Unit)?,
    onRevealClose: (() -> Unit)?,
    onSettle: (parked: Boolean) -> Unit,
    onCommit: (SwipeDirection) -> Unit
) {
    val slop = viewConfiguration.touchSlop
    val base = if (parked) 1f else 0f
    var axis = 0
    var totalX = 0f
    var totalY = 0f
    var committed = false
    var opened = false
    var closed = false
    var dragJob: Job? = null

    fun move(x: Float, p: Float) {
        dragJob?.cancel()
        dragJob = scope.launch {
            offsetX.snapTo(x)
            pull.snapTo(p)
        }
    }

    fun settleX() {
        dragJob?.cancel()
        dragJob = scope.launch {
            offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    detectDragGestures(
        onDragStart = {
            axis = 0
            totalX = 0f
            totalY = 0f
            committed = false
            opened = false
            closed = false
            dragging.value = true
        },
        onDrag = { change, dragAmount ->
            change.consume()
            if (committed) return@detectDragGestures
            totalX += dragAmount.x
            totalY += dragAmount.y
            if (axis == 0 && (abs(totalX) > slop || abs(totalY) > slop)) {
                axis = if (abs(totalX) > abs(totalY)) 1 else 2
            }
            when (axis) {
                1 -> {
                    val x = when {
                        allowLeft && totalX < 0 -> totalX
                        allowRight && totalX > 0 -> totalX
                        else -> 0f
                    }
                    move(x, pull.value)
                }

                2 -> {
                    // 读累计位移而不是 pull.value：snapTo 是异步的，同帧里还是旧值
                    val p = (base - totalY / thresholds.parkPx).coerceIn(0f, 1f)
                    move(offsetX.value, p)
                    if (!parked && onRevealOpen != null && !opened && -totalY >= thresholds.revealY) {
                        opened = true
                        onRevealOpen()
                    }
                    if (parked && onRevealClose != null && !closed && totalY >= thresholds.closeY) {
                        closed = true
                        onRevealClose()
                    }
                }
            }
        },
        onDragEnd = {
            dragging.value = false
            if (committed) return@detectDragGestures
            when (axis) {
                1 -> {
                    // 读累计位移：最后一次 snapTo 可能还排在队列里没生效
                    val direction = when {
                        allowLeft && totalX <= -thresholds.commitX -> SwipeDirection.LEFT
                        allowRight && totalX >= thresholds.commitX -> SwipeDirection.RIGHT
                        else -> null
                    }
                    if (direction == null) {
                        settleX()
                    } else {
                        committed = true
                        dragJob?.cancel()
                        scope.launch {
                            offsetX.animateTo(if (direction == SwipeDirection.LEFT) -thresholds.exitX else thresholds.exitX, tween(180))
                            onCommit(direction)
                        }
                    }
                }

                2 -> if (allowUp && -totalY >= thresholds.commitY) {
                    committed = true
                    dragJob?.cancel()
                    scope.launch {
                        pull.animateTo(2.5f, tween(180))
                        onCommit(SwipeDirection.UP)
                    }
                } else {
                    dragJob?.cancel()
                    val target = when {
                        parked && closed -> false
                        parked -> true
                        opened -> true
                        else -> false
                    }
                    onSettle(target)
                }

                else -> onSettle(parked)
            }
        },
        onDragCancel = {
            dragging.value = false
            settleX()
            onSettle(parked)
        }
    )
}
