package com.example.vocabwatch.ui.components

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch

/**
 * 旋转表圈一格大约折算成多少像素的滚动量。手感偏快或偏慢就调这个数。
 */
private const val ROTARY_PIXELS_PER_DETENT = 48f

/**
 * 旋转表圈滚动支持。
 *
 * Wear Compose 1.2.1 里没有 foundation.rotary 包（1.3 才引入 rotaryScrollable），
 * 所以只能从根 View 上截获 SOURCE_ROTARY_ENCODER 的原生事件。
 * 升级到 Wear Compose 1.3+ 之后，这个文件可以整体删掉，
 * 调用处换成 Modifier.rotaryScrollable(state) 即可。
 *
 * 关于监听器覆盖：SwipeDismissableNavHost 切页时，新页面的 DisposableEffect
 * 可能先于旧页面的 onDispose 执行。如果 onDispose 里无条件置 null，
 * 就会把新页面刚装上的监听器清掉。所以这里用 active 标志各自管自己的，
 * 旧监听器失效但不碰新的。
 */
@Composable
fun rememberRotaryScrollHandler(onScrollBy: suspend (pixels: Float) -> Unit) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    DisposableEffect(view) {
        var active = true

        view.setOnGenericMotionListener { _, event ->
            val isRotary = event.action == MotionEvent.ACTION_SCROLL &&
                (event.source and InputDevice.SOURCE_ROTARY_ENCODER) != 0
            if (isRotary && active) {
                // 顺时针为正，内容应当向下滚动
                val pixels = event.getAxisValue(MotionEvent.AXIS_SCROLL) * ROTARY_PIXELS_PER_DETENT
                scope.launch { onScrollBy(pixels) }
                true
            } else {
                false
            }
        }
        view.requestFocus()

        onDispose { active = false }
    }
}

/** 给任意 ScrollableState 接上旋转表圈，一行搞定。 */
@Composable
fun rememberRotaryScrollable(state: ScrollableState) {
    rememberRotaryScrollHandler { pixels -> state.scrollBy(pixels) }
}
