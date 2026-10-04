package top.southfish.engrave.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 非 Wear 手表版的 Material 3 主题。
 *
 * 配色对齐 Wear 版观感：黑底、青色主调；字形尺寸沿用手表的紧凑刻度
 * （迁移代码里的 labelSmall/bodyMedium/bodyLarge 等槽位即原
 * caption2/body2/body1 的映射落点）。按钮形状为胶囊形，贴近 Wear 观感。
 */

private val WatchColors = darkColorScheme(
    primary = Color(0xFF4DD8C0),
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF121417),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF252B34),
    onSurfaceVariant = Color(0xFFB9C4D0),
    error = Color(0xFFCF6679),
    onError = Color.Black,
    outline = Color(0xFF313842)
)

private val WatchTypography = Typography(
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
)

private val WatchShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(50),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/**
 * 设计基准宽度（dp）：Galaxy Watch4 = 450px @ 340dpi ≈ 212dp。
 * 整套 UI 的间距/字号都按此宽度调校。
 */
private const val BASELINE_WIDTH_DP = 212f

/** 缩放下限：保证窄表触摸目标和文字仍可用。 */
private const val SCALE_MIN = 0.85f

/** 缩放上限：避免在厂商 DPI 上报偏小的宽表上 UI 被放得过大、发虚。 */
private const val SCALE_MAX = 1.15f

@Composable
fun EngravePhoneTheme(content: @Composable () -> Unit) {
    // 非 Wear 廉价手表 densityDpi 上报普遍不准，dp 只能保证物理尺寸一致、
    // 保证不了一屏装得下。按屏幕实际 dp 宽相对基准宽度做全局等比缩放：
    // 所有 dp 尺寸与 sp 字号按同一系数放大/缩小，UI 观感保持 Watch4 原样。
    // fontScale 固定 1，不跟系统字号设置走——手表紧凑布局经不起大字体破版。
    val widthDp = LocalConfiguration.current.screenWidthDp
    val scale = (widthDp / BASELINE_WIDTH_DP).coerceIn(SCALE_MIN, SCALE_MAX)
    val systemDensity = LocalDensity.current
    val scaledDensity = Density(systemDensity.density * scale, fontScale = 1f)

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(
            colorScheme = WatchColors,
            typography = WatchTypography,
            shapes = WatchShapes,
            content = content
        )
    }
}
