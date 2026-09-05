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
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** 卡片手势的距离阈值，单位 px，由调用方按屏幕密度换算好。 */
internal class CardSwipeThresholds(
    /** 横向拖过这个距离松手就算右滑成立 */
    val commitX: Float,
    /** 纵向拖过这个距离松手就算上滑成立 */
    val commitY: Float,
    /** 浏览阶段上拉多少开始露出例句 */
    val revealY: Float,
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
    onDoubleTap: () -> Unit
) {
    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
    awaitEachGesture {
        awaitFirstDown()
        if (waitForUpOrCancellation() == null) return@awaitEachGesture // 拖走了，交给拖拽检测
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
 * 第一次移动超过触摸阈值时锁定主轴，之后只沿该轴位移，避免斜着拖时
 * 卡片同时横飞又上抬。飞出动画跑完才回调 onCommit，所以卡片内容在
 * 动画期间不会被状态机换掉。
 */
internal suspend fun PointerInputScope.detectCardSwipe(
    scope: CoroutineScope,
    offsetX: Animatable<Float, AnimationVector1D>,
    offsetY: Animatable<Float, AnimationVector1D>,
    thresholds: CardSwipeThresholds,
    allowRight: Boolean,
    allowUp: Boolean,
    onPullReveal: (() -> Unit)?,
    onCommit: (SwipeDirection) -> Unit
) {
    val slop = viewConfiguration.touchSlop
    var axis = 0
    var totalX = 0f
    var totalY = 0f
    var committed = false
    var revealed = false
    var dragJob: Job? = null

    fun moveTo(x: Float, y: Float) {
        dragJob?.cancel()
        dragJob = scope.launch {
            offsetX.snapTo(x)
            offsetY.snapTo(y)
        }
    }

    fun settle() {
        dragJob?.cancel()
        dragJob = scope.launch {
            offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            offsetY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    detectDragGestures(
        onDragStart = {
            axis = 0
            totalX = 0f
            totalY = 0f
            committed = false
            revealed = false
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
                1 -> if (allowRight) moveTo(totalX.coerceAtLeast(0f), 0f)
                2 -> {
                    if (allowUp || onPullReveal != null) moveTo(0f, totalY.coerceAtMost(0f))
                    // 读累计位移而不是 offsetY.value：snapTo 是异步的，同帧里还是旧值
                    if (onPullReveal != null && !revealed && -totalY >= thresholds.revealY) {
                        revealed = true
                        onPullReveal()
                    }
                }
            }
        },
        onDragEnd = {
            if (committed) return@detectDragGestures
            when (axis) {
                1 -> if (allowRight && offsetX.value >= thresholds.commitX) {
                    committed = true
                    dragJob?.cancel()
                    scope.launch {
                        offsetX.animateTo(thresholds.exitX, tween(180))
                        onCommit(SwipeDirection.RIGHT)
                    }
                } else {
                    settle()
                }

                2 -> if (allowUp && -offsetY.value >= thresholds.commitY) {
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
        onDragCancel = { settle() }
    )
}
