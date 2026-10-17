package top.southfish.engrave.ui.components

import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 事件分发的基准像素/格；rememberRotaryScrollable 实际按视口百分比换算，见下。 */
private const val ROTARY_PIXELS_PER_DETENT = 120f

/** 表圈每格滚动距离 = 可见区域高度 × 这个比例。手感偏快或偏慢就调它。 */
private const val ROTARY_VIEWPORT_FRACTION = 0.20f

/** 震动的最小间隔：快速连转时低于这个间隔的格不再出声，避免震个不停。 */
private const val HAPTIC_MIN_INTERVAL_MS = 100L

/**
 * 表圈事件的分发中枢。
 *
 * 第二轮修复：彻底放弃 View.setOnGenericMotionListener 路线——它有两个
 * 绕不开的坑：listener 是**单槽位**（多处挂互相覆盖，首页滚轮失灵的根因），
 * 而且依赖 requestFocus 抢焦点，这台 Watch4（One UI Watch）上焦点链路不可靠，
 * 第一轮修复后表圈依旧没反应。
 *
 * 现在改走 Activity 兜底：generic motion 事件沿视图树派发，无人消费时最终
 * 落到 Activity.onGenericMotionEvent（见 MainActivity），由它调 [dispatch]
 * 交给当前页面注册的 handler。不依赖焦点，也没有槽位覆盖问题——同一时刻
 * 只有最新注册的 handler 生效。
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
    hapticOnEvent: Boolean = true,
    onScrollBy: suspend (pixels: Float) -> Unit
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    // 永远转发到最新的回调：lambda 闭包捕获的是 State 代理，每次读都是新值
    val latestOnScrollBy by rememberUpdatedState(onScrollBy)

    DisposableEffect(enabled, hapticOnEvent) {
        var lastHapticAt = 0L
        val unregister = RotaryInput.register { pixels ->
            if (!enabled) return@register false
            // 每格一声轻「嗒」：CLOCK_TICK 是系统为表圈/转盘设计的震动，跟随系统震动开关；
            // 快速连转按最小间隔节流。列表页不用这套（hapticOnEvent=false），
            // 改成由「真实滚动了」触发，滚到尽头自动安静。
            if (hapticOnEvent) {
                val now = System.currentTimeMillis()
                if (now - lastHapticAt >= HAPTIC_MIN_INTERVAL_MS) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    lastHapticAt = now
                }
            }
            scope.launch { latestOnScrollBy(pixels) }
            true
        }
        onDispose { unregister() }
    }
}

/** 按真实滚动量触发的节流震动：列表真滚了才「嗒」，到尽头消耗为 0 自然不震。 */
private class RotaryHaptics(private val view: android.view.View) {
    private var lastAt = 0L

    fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastAt >= HAPTIC_MIN_INTERVAL_MS) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastAt = now
        }
    }
}

/**
 * 滚轮滚动蓄积器：每格的滚动量先累加，由一个协程按帧「追赶」——每帧吃掉
 * 剩余距离的 [CHASE_PER_FRAME]，剩余越多吃得越快。不用动画规格的原因：
 * spring 的时长随距离变长，快速连转时播放速度跟不上输入，队列越排越长，
 * 看起来反而变慢；追赶式落后越多追得越快，天然跟手。
 */
private class PendingRotaryScroll(
    private val stateProvider: () -> ScrollableState,
    private val scope: CoroutineScope,
    private val viewportPx: () -> Int = { 0 },
    private val onConsumed: () -> Unit = {}
) {
    private var accumulated = 0f
    private var job: Job? = null

    @Synchronized
    fun add(delta: Float) {
        accumulated += delta
        if (job?.isActive != true) {
            job = scope.launch {
                while (true) {
                    val acc = accumulated
                    if (abs(acc) <= 0.5f) break
                    withFrameNanos { }
                    // 积压超过两屏（=猛转）时一帧到位，像系统列表 fling 落地；
                    // 否则按 [CHASE_PER_FRAME] 追赶滑翔，小转保持顺滑
                    val vh = viewportPx()
                    val step = if (vh > 0 && abs(acc) >= 2f * vh) acc else acc * CHASE_PER_FRAME
                    val actual = stateProvider().dispatchRawDelta(step)
                    if (abs(actual) < 0.01f) {
                        // 到尽头滚不动了，丢弃剩余量防死循环
                        accumulated = 0f
                        break
                    }
                    accumulated -= actual
                    if (abs(actual) >= 2f) onConsumed()
                }
            }
        }
    }
}

/** 追赶式滚动每帧吃掉剩余距离的比例。 */
private const val CHASE_PER_FRAME = 0.5f

/** 给列表接上旋转表圈：每格 = 可见区域高度的 [ROTARY_VIEWPORT_FRACTION]，随屏幕大小走。 */
@Composable
fun rememberRotaryScrollable(state: ScalingLazyListState, enabled: Boolean = true) =
    rememberRotaryScrollable(state as ScrollableState, enabled) { state.layoutInfo.viewportSize.height }

/** 给其他滚动状态（如网格）接表圈：没有视口概念，走基准像素。 */
@Composable
fun rememberRotaryScrollable(
    state: ScrollableState,
    enabled: Boolean = true,
    viewportSize: () -> Int = { 0 }
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val haptics = remember { RotaryHaptics(view) }
    val pending = remember(state) {
        PendingRotaryScroll({ state }, scope, viewportPx = viewportSize, onConsumed = { haptics.tick() })
    }
    rememberRotaryScrollHandler(enabled = enabled, hapticOnEvent = false) { pixels ->
        // dispatch 给的是基准像素；有视口尺寸就换算成视口高度百分比
        val viewport = viewportSize()
        if (viewport > 0) {
            pending.add(pixels / ROTARY_PIXELS_PER_DETENT * viewport * ROTARY_VIEWPORT_FRACTION)
        } else {
            pending.add(pixels)
        }
    }
}

/**
 * 目标列表会动态切换的页面（如搜索页按输入/分页在历史、单词、短语表间切换）：
 * 每次事件实时取当前目标列表，其余行为与 [rememberRotaryScrollable] 一致。
 */
@Composable
fun rememberDynamicRotaryScroll(enabled: Boolean = true, state: () -> ScalingLazyListState) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val haptics = remember { RotaryHaptics(view) }
    val latestTarget by rememberUpdatedState(state)
    val pending = remember {
        PendingRotaryScroll(
            stateProvider = { latestTarget() },
            scope = scope,
            viewportPx = { latestTarget().layoutInfo.viewportSize.height },
            onConsumed = { haptics.tick() }
        )
    }
    rememberRotaryScrollHandler(enabled = enabled, hapticOnEvent = false) { pixels ->
        val viewport = latestTarget().layoutInfo.viewportSize.height
        if (viewport > 0) {
            pending.add(pixels / ROTARY_PIXELS_PER_DETENT * viewport * ROTARY_VIEWPORT_FRACTION)
        } else {
            pending.add(pixels)
        }
    }
}

