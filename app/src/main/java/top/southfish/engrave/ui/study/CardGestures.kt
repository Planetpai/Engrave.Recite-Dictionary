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
 * 点击翻面 + 三击撤销 + 长按加错题。
 *
 * 几种点击和拖拽放在同一个检测器里、自定仲裁：
 * - 不用 detectTapGestures：它设了 onDoubleTap 之后，单击要等双击超时才回调，
 *   手表上翻面会明显拖一下。这里第一下抬手就立刻翻面，再等连点窗口，
 *   三击成立才撤销——翻面紧跟着被撤销覆盖，观感正是「卡片飞回来」。
 * - 第二击不干等：立刻当普通点击翻面，窗口内第三击再来才补撤销。
 *   这样「点两下翻回去」不受惩罚，误触两下也不会撤销。
 * - 不能和独立的 detectTapGestures / detectDragGestures 并存再靠事件消费
 *   仲裁：同一节点上多个检测器各跑各的，框架自己的点击/拖拽检测会消费
 *   down/up 事件，外层检测器用 waitForUpOrCancellation 会把普通点击也误判成
 *   「被抢走」。这里自己按 touchSlop 判定是否拖走、按时长判定长按，
 *   只读取事件的原始位置和状态，完全不看消费标记。
 *
 * 返回 null 表示这一下是拖拽（卡片在跟着手走），点击逻辑不做任何响应。
 */
internal suspend fun PointerInputScope.detectFlipAndUndo(
    onTap: () -> Unit,
    onTripleTap: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    val slop = viewConfiguration.touchSlop

    // 等手指抬起：期间识别「拖过 slop（交拖拽）」和「按够长按时长」。
    // 长按只在有人监听时才计时。超时窗口按事件时间戳推进，不依赖墙钟。
    suspend fun AwaitPointerEventScope.awaitPressEnd(
        down: PointerInputChange
    ): Pair<TapOutcome, Long?> {
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
                ?: return TapOutcome.CANCELLED to null

            // 位移超过 touch slop：这是拖动手势，交给拖拽检测，点击退出
            val moved = change.position - start
            if (abs(moved.x) > slop || abs(moved.y) > slop) {
                return TapOutcome.DRAGGED_AWAY to null
            }

            if (!change.pressed) {
                // 抬手了
                val held = change.uptimeMillis - downTime
                return when {
                    longPressFired || (onLongPress != null && held >= longPressTimeout) ->
                        TapOutcome.LONG_PRESS to held
                    else -> TapOutcome.TAP to held
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

        val (outcome, _) = awaitPressEnd(down)
        if (outcome != TapOutcome.TAP) return@awaitEachGesture

        // 第一下抬手立即翻面
        onTap()

        // 连点链：第二击同样立刻翻面（保持响应速度），第三击在窗口内
        // 到达才算撤销。任何一击被拖走 / 长按 / 取消，链就断——防误触。
        var taps = 1
        while (taps < 3) {
            val chained = withTimeoutOrNull(doubleTapTimeout) {
                val nextDown = awaitFirstDown(requireUnconsumed = false)
                val (nextOutcome, _) = awaitPressEnd(nextDown)
                nextOutcome == TapOutcome.TAP
            } ?: false
            if (!chained) return@awaitEachGesture
            taps++
            if (taps < 3) onTap()
        }
        onTripleTap()
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
