package com.example.vocabwatch.ui.components

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/**
 * 数字转轮（像系统的倒计时选择器）：上下拖动数字，停在中间的值就是选中值。
 *
 * 用 LazyColumn + SnapFlingBehavior 实现，item 高 [ITEM_HEIGHT]，上下留出
 * 等宽 contentPadding 让「中间槽位」对准视口中心。停止滚动时把选中的值
 * 回调给 [onSelected]。
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

    // 停稳（对齐 offset≈0）时的中央 item 就是选中值
    val settledIndex by remember {
        derivedStateOf {
            val centerOffset = listState.firstVisibleItemScrollOffset
            val idx = listState.firstVisibleItemIndex
            if (centerOffset < 5) idx else -1
        }
    }
    LaunchedEffect(settledIndex) {
        val idx = settledIndex
        if (idx >= 0 && idx < values.size && values[idx] != selected) {
            onSelected(values[idx])
        }
    }
    // 外部值变化（例如导入数据恢复）时滚过去
    LaunchedEffect(selected) {
        val target = values.indexOf(selected)
        if (target >= 0 && target != listState.firstVisibleItemIndex) {
            listState.scrollToItem(target)
        }
    }

    Box(modifier.height(WHEEL_HEIGHT), contentAlignment = Alignment.Center) {
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = (WHEEL_HEIGHT - ITEM_HEIGHT) / 2),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(values.size) { index ->
                val isCenter = index == settledIndex
                Box(
                    Modifier
                        .fillMaxWidth()
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
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private val WHEEL_HEIGHT = 102.dp
private val ITEM_HEIGHT = 34.dp
