package top.southfish.engrave.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.delay

/**
 * 横滑翻页的小圆点提示（几页几颗，当前页亮、其余暗）。
 *
 * 只在两个时机出现：进入页面时短暂显示约 1.5 秒后淡出；
 * 左右滑动期间显示，停止约 0.8 秒后淡出。纯提示层，不响应点击。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageDots(pagerState: PagerState, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(true) }
    var wasScrolling by remember { mutableStateOf(false) }

    // 进页短暂显示后淡出（正在滑动就让位给下面的滑动逻辑）
    LaunchedEffect(Unit) {
        delay(1500)
        if (!pagerState.isScrollInProgress) visible = false
    }

    // 滑动期间显示；停止后延时淡出。键值翻转即滑动开始/结束的边界
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) {
            visible = true
        } else if (wasScrolling) {
            delay(800)
            visible = false
        }
        wasScrolling = pagerState.isScrollInProgress
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = if (visible) 150 else 300)
    )
    Row(
        modifier = modifier.alpha(alpha),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pagerState.pageCount) { index ->
            val active = index == pagerState.currentPage
            val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 0.85f else 0.3f)
            val size by animateDpAsState(if (active) 5.dp else 4.dp)
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}
