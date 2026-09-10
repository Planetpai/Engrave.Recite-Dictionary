package top.southfish.engrave.ui.components

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlin.math.abs

/**
 * 数字转轮（像系统的倒计时选择器）：上下拖动数字，停在中间的值就是选中值。
 *
 * 用 LazyColumn + SnapFlingBehavior 实现，item 高 [ITEM_HEIGHT]，上下留出
 * 等宽 contentPadding 让「中间槽位」对准视口中心。滚动停稳（或拖住不动）时
 * 把中央的值回调给 [onSelected]。
 *
 * 手势响应区宽度自动测量为「最宽一档文字」的宽度（两侧仅留 4dp 余量），
 * 与数字显示区域等宽：转轮两侧的空白区域不再吞掉滑动，圆屏上不会误触。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NumberWheel(
    values: List<Int>,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    format: (Int) -> String = { it.toString() }
) {
    val initialIndex = values.indexOf(selected).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val flingBehavior = rememberSnapFlingBehavior(listState)

    // 选中值 = 中心离视口中心最近的那个可见 item。
    // 不能用 firstVisibleItemIndex 凑：列表滚到尽头时首可见项是倒数第二个，
    // 最后一档（55 分 / 23 时）就永远定不住——这正是当年 55 分 bug 的根源。
    val centeredIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) return@derivedStateOf -1
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo
                .minByOrNull { abs(it.offset + it.size / 2 - center) }
                ?.index ?: -1
        }
    }
    // 滚动进行中不回调；停稳（包括拖住不动的间隙）立刻回调，标签能实时跟上
    val settledIndex = if (listState.isScrollInProgress) -1 else centeredIndex
    LaunchedEffect(settledIndex) {
        val idx = settledIndex
        if (idx >= 0 && idx < values.size && values[idx] != selected) {
            onSelected(values[idx])
        }
    }
    // 外部值变化（例如导入数据恢复）时滚过去；该 item 到 offset 0 正是中央槽位
    LaunchedEffect(selected) {
        val target = values.indexOf(selected)
        if (target >= 0 && target != listState.firstVisibleItemIndex) {
            listState.scrollToItem(target)
        }
    }

    // 触摸条宽度 = 最宽一档文字的宽度（中央选中档 title2 字样最大，按它量），
    // 两侧各留 4dp 余量。measure 都是短字符串，每次组合重算开销可忽略
    val textMeasurer = rememberTextMeasurer()
    val centerStyle = MaterialTheme.typography.title2
    val maxTextWidthPx = values.maxOf { value ->
        textMeasurer.measure(text = AnnotatedString(format(value)), style = centerStyle).size.width
    }
    val touchWidth = with(LocalDensity.current) { maxTextWidthPx.toDp() } + TOUCH_SIDE_PADDING * 2

    Box(modifier.height(WHEEL_HEIGHT), contentAlignment = Alignment.Center) {
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = (WHEEL_HEIGHT - ITEM_HEIGHT) / 2),
            modifier = Modifier.width(touchWidth)
        ) {
            items(values.size) { index ->
                val isCenter = index == settledIndex
                Box(
                    Modifier
                        .width(touchWidth)
                        .height(ITEM_HEIGHT),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = format(values[index]),
                        style = if (isCenter) {
                            MaterialTheme.typography.title2
                        } else {
                            MaterialTheme.typography.body2
                        },
                        color = if (isCenter) {
                            MaterialTheme.colors.onSurface
                        } else {
                            MaterialTheme.colors.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(touchWidth)
                    )
                }
            }
        }
    }
}

private val WHEEL_HEIGHT = 102.dp
private val ITEM_HEIGHT = 34.dp

/** 触摸条在文字两侧各留的余量：完全贴字形边缘不好按，4dp 视觉上仍与数字等宽 */
private val TOUCH_SIDE_PADDING = 4.dp
