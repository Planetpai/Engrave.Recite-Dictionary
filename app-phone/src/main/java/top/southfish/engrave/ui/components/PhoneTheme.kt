package top.southfish.engrave.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun EngravePhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WatchColors,
        typography = WatchTypography,
        shapes = WatchShapes,
        content = content
    )
}
