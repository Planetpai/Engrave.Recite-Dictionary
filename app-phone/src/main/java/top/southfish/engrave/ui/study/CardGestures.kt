package top.southfish.engrave.ui.study

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
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
    /** 卡片飞出去的目标位移 */
    val exitX: Float,
    val exitY: Float
)

/** 一次「按下去→抬手」的结果。 */
private enum class TapOutcome { TAP, LONG_PRESS, DRAGGED_AWAY, CANCELLED }

/**
 * 卡片上的点击翻面 + 长按加错题。
 *
 * 撤销不在卡片上（见 [detectOutsideDoubleTap]）：卡片上只认单击和长按，
 * 连点只负责翻面，怎么点都不会误触撤销。
 *
 * 点击和拖拽放在同一个检测器里、自定仲裁：
 * - 不用 detectTapGestures：单击抬手立刻回调，翻面没有任何等待感。
 * - 不能和独立的 detectTapGestures / detectDragGestures 并存再靠事件消费
 *   仲裁：同一节点上多个检测器各跑各的，框架自己的点击/拖拽检测会消费
 *   down/up 事件，外层检测器用 waitForUpOrCancellation 会把普通点击也误判成
 *   「被抢走」。这里自己按 touchSlop 判定是否拖走、按时长判定长按，
 *   只读取事件的原始位置和状态，完全不看消费标记。
 *
 * 拖走或长按时点击逻辑不做任何响应。
 */
internal suspend fun PointerInputScope.detectTapAndLongPress(
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    val slop = viewConfiguration.touchSlop

    // 等手指抬起：期间识别「拖过 slop（交拖拽）」和「按够长按时长」。
    // 长按只在有人监听时才计时。超时窗口按事件时间戳推进，不依赖墙钟。
    suspend fun AwaitPointerEventScope.awaitPressEnd(
        down: PointerInputChange
    ): TapOutcome {
        val downTime = down.uptimeMillis
        val start = down.position
        var waitMs: Long = if (onLongPress != null) longPressTimeout else Long.MAX_VALUE
        var longPressFired = false

        while (true) {
            val event = withTimeoutOrNull(waitMs) { awaitPointerEvent() }
            if (event == null) {
                // 到了长按时限：手指还按着、没拖走 → 触发长按
                onLongPress?.invoke()
                longPressFired = true
                waitMs = Long.MAX_VALUE
                continue
            }

            val change = event.changes.firstOrNull { it.id == down.id }
                ?: return TapOutcome.CANCELLED

            // 位移超过 touch slop：这是拖动手势，交给拖拽检测，点击退出
            val moved = change.position - start
            if (abs(moved.x) > slop || abs(moved.y) > slop) {
                return TapOutcome.DRAGGED_AWAY
            }

            if (!change.pressed) {
                // 抬手了
                val held = change.uptimeMillis - downTime
                return when {
                    longPressFired || (onLongPress != null && held >= longPressTimeout) ->
                        TapOutcome.LONG_PRESS
                    else -> TapOutcome.TAP
                }
            }

            // 手指还按着：按最新事件时间重算距长按超时还剩多久
            waitMs = if (onLongPress != null && !longPressFired) {
                (downTime + longPressTimeout - change.uptimeMillis).coerceAtLeast(1L)
            } else {
                Long.MAX_VALUE
            }
        }
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val outcome = awaitPressEnd(down)
        if (outcome == TapOutcome.TAP) onTap()
    }
}

/**
 * 卡片框外区域的双击撤销。
 *
 * 为什么撤销放在卡片外：卡片本身要承载单击翻面、左滑/上滑评分、长按加错题，
 * 以前的三击撤销和翻面点按共用一块区域，翻得快一点就会误触。现在双击的
 * 落点必须在卡片矩形之外（周围留白环）才成立，卡片上怎么连点都只翻面。
 *
 * 自己识别而不是直接挂 detectTapGestures：每次 down 都先用 [excludedRect]
 * 过滤，落在卡片上的手势整段放弃，不会与卡片自己的检测器互相干扰；
 * 第二击超时、拖走或取消都不触发。
 */
internal suspend fun PointerInputScope.detectOutsideDoubleTap(
    excludedRect: () -> Rect?,
    onDoubleTap: () -> Unit
) {
    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis

    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        val rect = excludedRect()
        if (rect != null && firstDown.position in rect) return@awaitEachGesture
        if (!waitForTapUp(firstDown)) return@awaitEachGesture

        val secondDown = withTimeoutOrNull(doubleTapTimeout) {
            awaitFirstDown(requireUnconsumed = false)
        } ?: return@awaitEachGesture
        if (rect != null && secondDown.position in rect) return@awaitEachGesture
        if (!waitForTapUp(secondDown)) return@awaitEachGesture

        onDoubleTap()
    }
}

/** 等到这根手指原地抬手；发生拖动或手势取消都返回 false。 */
private suspend fun AwaitPointerEventScope.waitForTapUp(
    down: PointerInputChange
): Boolean {
    val slop = viewConfiguration.touchSlop
    val start = down.position
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return false
        val moved = change.position - start
        if (abs(moved.x) > slop || abs(moved.y) > slop) return false
        if (!change.pressed) return true
    }
}

/**
 * 拖动跟手 + 松手后飞出或回弹。
 *
 * 横向：[allowLeft] / [allowRight] 决定哪个方向能拖动，「已学过 / 会」走左滑，
 * 特意避开 Wear 从左缘右滑返回的系统手势。
 *
 * 纵向和横向完全对称：只有被手指按住的这张卡片跟着走（offsetY 跟手上移），
 * 下面一叠卡保持不动；上拉过 [commitY] 松手就从屏幕上方飞出算「不会」，
 * 没到阈值就弹簧回原位。没有中间停驻态，卡片也不会缩小。
 * 所有阈值都相对手势起点。
 */
internal suspend fun PointerInputScope.detectCardSwipe(
    scope: CoroutineScope,
    dragging: MutableState<Boolean>,
    offsetX: Animatable<Float, AnimationVector1D>,
    offsetY: Animatable<Float, AnimationVector1D>,
    thresholds: CardSwipeThresholds,
    allowLeft: Boolean,
    allowRight: Boolean,
    allowUp: Boolean,
    onCommit: (SwipeDirection) -> Unit
) {
    val slop = viewConfiguration.touchSlop
    var axis = 0
    var totalX = 0f
    var totalY = 0f
    var committed = false
    var dragJob: Job? = null

    fun move(x: Float, y: Float) {
        dragJob?.cancel()
        dragJob = scope.launch {
            offsetX.snapTo(x)
            offsetY.snapTo(y)
        }
    }

    fun settle() {
        dragJob?.cancel()
        dragJob = scope.launch {
            launch { offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
            launch { offsetY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
        }
    }

    detectDragGestures(
        onDragStart = {
            axis = 0
            totalX = 0f
            totalY = 0f
            committed = false
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
                    move(x, offsetY.value)
                }

                2 -> {
                    // 只允许上拉跟手，向下不许拖动（和横向只允许左滑同理）
                    val y = if (allowUp && totalY < 0) totalY else 0f
                    move(offsetX.value, y)
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
                        settle()
                    } else {
                        committed = true
                        dragJob?.cancel()
                        scope.launch {
                            offsetX.animateTo(
                                if (direction == SwipeDirection.LEFT) -thresholds.exitX else thresholds.exitX,
                                tween(180)
                            )
                            onCommit(direction)
                        }
                    }
                }

                2 -> if (allowUp && -totalY >= thresholds.commitY) {
                    committed = true
                    dragJob?.cancel()
                    scope.launch {
                        offsetY.animateTo(-thresholds.exitY, tween(180))
                        onCommit(SwipeDirection.UP)
                    }
                } else {
                    settle()
                }

                else -> settle()
            }
        },
        onDragCancel = {
            dragging.value = false
            settle()
        }
    )
}
