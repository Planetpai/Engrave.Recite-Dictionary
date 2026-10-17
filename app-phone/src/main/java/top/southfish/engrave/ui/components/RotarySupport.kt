package top.southfish.engrave.ui.components

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch

/** 旋转表圈一格大约折算成多少像素的滚动量。手感偏快或偏慢就调这个数。 */
private const val ROTARY_PIXELS_PER_DETENT = 48f

/**
 * 表圈事件的分发中枢。
 *
 * 第二轮修复：彻底放弃 View.setOnGenericMotionListener 路线——它有两个
 * 绕不开的坑：listener 是**单槽位**（多处挂互相覆盖，首页滚轮失灵的根因），
 * 而且依赖 requestFocus 抢焦点，这台 Watch4（One UI Watch）上焦点链路不可靠，
 * 第一轮修复后表圈依旧没反应。
 *
 * 现在改走 Activity 兜底：generic motion 事件沿视图树派发，无人消费时最终
 * 落到 Activity.onGenericMotionEvent（见手表端 MainActivity），由它调
 * [dispatch] 交给当前页面注册的 handler。不依赖焦点，也没有槽位覆盖问题——
 * 同一时刻只有最新注册的 handler 生效。手机端没有表圈硬件，此机制闲置。
 */
object RotaryInput {
    private var seq = 0
    private var handler: ((Float) -> Boolean)? = null

    /** 注册当前页面的 handler，返回注销函数（onDispose 里调用）。 */
    fun register(h: (Float) -> Boolean): () -> Unit {
        val mySeq = ++seq
        handler = h
        return {
            // 只注销自己这轮：SwipeDismissableNavHost 切页时新页面可能先注册、
            // 旧页面后 onDispose，无条件置 null 会把新的清掉
            if (mySeq == seq) handler = null
        }
    }

    /** Activity 兜底入口：是表圈滚动就分发给当前 handler，返回是否消费。 */
    fun dispatch(event: MotionEvent): Boolean {
        val isRotary = event.action == MotionEvent.ACTION_SCROLL &&
            (event.source and InputDevice.SOURCE_ROTARY_ENCODER) != 0
        if (!isRotary) return false
        val h = handler ?: return false
        // 顺时针为正，内容应当向下滚动
        val pixels = event.getAxisValue(MotionEvent.AXIS_SCROLL) * ROTARY_PIXELS_PER_DETENT
        return h(pixels)
    }
}

/**
 * 注册当前页面的表圈滚动回调。
 * 升级到 Wear Compose 1.3+ 后可整体删掉，换成 Modifier.rotaryScrollable(state)。
 */
@Composable
fun rememberRotaryScrollHandler(
    enabled: Boolean = true,
    onScrollBy: suspend (pixels: Float) -> Unit
) {
    val scope = rememberCoroutineScope()
    // 永远转发到最新的回调：lambda 闭包捕获的是 State 代理，每次读都是新值
    val latestOnScrollBy by rememberUpdatedState(onScrollBy)

    DisposableEffect(enabled) {
        val unregister = RotaryInput.register { pixels ->
            if (!enabled) return@register false
            scope.launch { latestOnScrollBy(pixels) }
            true
        }
        onDispose { unregister() }
    }
}

/** 给任意 ScrollableState 接上旋转表圈，一行搞定。enabled=false 时让给别的页面。 */
@Composable
fun rememberRotaryScrollable(state: ScrollableState, enabled: Boolean = true) {
    // animateScrollBy：每格滚动走 spring 动画而不是瞬时硬跳（与 Wear 端保持一致手感）
    rememberRotaryScrollHandler(enabled = enabled) { pixels -> state.animateScrollBy(pixels) }
}
