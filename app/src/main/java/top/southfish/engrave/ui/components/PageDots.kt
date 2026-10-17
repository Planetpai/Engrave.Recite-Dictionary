package top.southfish.engrave.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.PageIndicatorState
import kotlinx.coroutines.delay

/** 停手后小圆点自动淡出的等待时长。 */
private const val HIDE_DELAY_MS = 2500L

/**
 * 页面切换小圆点：绘制交给官方 HorizontalPageIndicator（圆屏自动弧形排列），
 * 外面包一层自动隐藏——翻页/滑动时显示，停手约 [HIDE_DELAY_MS] 后淡出。
 * 官方组件本身常显、不会自动消失，这层行为是本项目自己要的（2026-10-16 恢复）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageDots(pagerState: PagerState, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(pagerState.isScrollInProgress, pagerState.currentPage) {
        if (pagerState.isScrollInProgress) {
            visible = true
        } else {
            delay(HIDE_DELAY_MS)
            visible = false
        }
    }

    val indicatorState = remember(pagerState) {
        object : PageIndicatorState {
            override val pageCount: Int get() = pagerState.pageCount
            override val pageOffset: Float get() = pagerState.currentPageOffsetFraction
            override val selectedPage: Int get() = pagerState.currentPage
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        HorizontalPageIndicator(pageIndicatorState = indicatorState)
    }
}
