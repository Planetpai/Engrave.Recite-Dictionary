package top.southfish.engrave.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material.TimeText

/**
 * 带淡入淡出的顶部时间条：只变透明度、不动位置——圆屏（弧形字）与方屏
 * （普通字）的位置交给 Wear 的 TimeText 各自适配，两种屏上动画表现一致。
 */
@Composable
fun AnimatedTimeText(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 300)),
        exit = fadeOut(animationSpec = tween(durationMillis = 300))
    ) {
        TimeText()
    }
}

/**
 * 非学习页的顶部时间条显隐：列表停在顶部时常驻；往下翻内容（离开顶部）
 * 就隐藏；往回滑（向顶部滚）时再显示。只控显隐，位置不动。
 *
 * 方向判定把「item 序号 + 偏移」折成一个可比较的滚动位置再比大小：
 * 直接比 offset 会在翻过 item 的瞬间（offset 归零）被误判成往回滑。
 * 首帧只记位置不判方向——进页面先显示时间，动起来再按规则走。
 */
@Composable
fun rememberTimeTextVisible(listState: ScalingLazyListState): Boolean =
    rememberScrollAwareTimeVisible(listState) {
        // ScalingLazyListState 只有 centerItemIndex/centerItemScrollOffset
        listState.centerItemIndex to listState.centerItemScrollOffset
    }

/** 头像选择页的相册网格是 LazyVerticalGrid，同一套规则按 LazyGridState 再走一遍。 */
@Composable
fun rememberTimeTextVisible(gridState: LazyGridState): Boolean =
    rememberScrollAwareTimeVisible(gridState) {
        gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
    }

@Composable
private fun rememberScrollAwareTimeVisible(
    state: Any,
    position: () -> Pair<Int, Int>
): Boolean {
    var visible by remember { mutableStateOf(true) }
    var lastPos by remember { mutableStateOf(-1) }

    LaunchedEffect(state) {
        snapshotFlow(position).collect { (index, offset) ->
            val pos = index * 100_000 + offset
            visible = when {
                index == 0 && offset == 0 -> true // 列表在顶部
                lastPos < 0 -> true               // 首帧只记位置，进页默认显示
                pos < lastPos -> true             // 往回滑
                pos > lastPos -> false            // 向下翻内容，离开顶部
                else -> visible                   // 没动，保持现状
            }
            lastPos = pos
        }
    }
    return visible
}
