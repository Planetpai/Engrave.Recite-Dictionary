package top.southfish.engrave.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 屏幕是否为圆形。Wear 模拟器/真表通过 [Configuration.SCREEN_ROUND] 上报，
 * 方形表盘返回 false。
 */
val isScreenRound: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalConfiguration.current.isScreenRound

/**
 * 列表顶部为 TimeText 预留的高度。
 *
 * 圆形表盘顶部两侧是弧形黑边，时间浮在弧区，列表首项天然不与它相撞；
 * 方形表盘时间横在顶部正中（约 22dp 高），不额外留白就会压住首行文字
 * （引导页标题、状态行在方形屏实测被遮挡）。方形屏统一让出 28dp。
 */
@Composable
@ReadOnlyComposable
fun timeTextPadding(roundScreenTop: Dp = 0.dp): Dp =
    if (isScreenRound) roundScreenTop else SQUARE_SCREEN_TOP_PADDING

/** 方形屏 TimeText 高度（约 22dp）+ 6dp 呼吸距。 */
private val SQUARE_SCREEN_TOP_PADDING = 28.dp
