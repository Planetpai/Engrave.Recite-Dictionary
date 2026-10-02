package top.southfish.engrave.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Wear Compose 组件在 Material 3 下的等价实现（仅 :app-phone 使用）。
 *
 * 刻意沿用 Wear 版的 API 形状（Chip/ChipDefaults/ListHeader/CompactButton），
 * 让迁移过来的界面代码只改 import、不改调用点。视觉上对齐手表形态：
 * 胶囊形 Chip、大触点高度、次级文字灰色。
 */

/** Chip 配色容器，对应 wear ChipColors。 */
class ChipColors(
    val containerColor: Color,
    val contentColor: Color,
    val secondaryContainerColor: Color,
    val secondaryContentColor: Color
)

object ChipDefaults {

    @Composable
    fun chipColors(
        backgroundColor: Color = MaterialTheme.colorScheme.surface,
        contentColor: Color = MaterialTheme.colorScheme.onSurface
    ): ChipColors = ChipColors(
        containerColor = backgroundColor,
        contentColor = contentColor,
        secondaryContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        secondaryContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 手表风格列表项（对应 wear Chip）：胶囊形、整行可点。
 * [label] 主文字，[secondaryLabel] 次行小字，[icon] 行首图标。
 */
@Composable
fun Chip(
    label: @Composable RowScope.() -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: (@Composable RowScope.() -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    colors: ChipColors = ChipDefaults.chipColors(),
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(percent = 50),
        color = colors.containerColor,
        contentColor = colors.contentColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val rowScope = this
            if (icon != null) {
                icon()
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium) {
                    label(rowScope)
                }
                if (secondaryLabel != null) {
                    Spacer(Modifier.height(2.dp))
                    CompositionLocalProvider(
                        LocalTextStyle provides MaterialTheme.typography.labelSmall.copy(
                            color = colors.secondaryContentColor
                        )
                    ) {
                        secondaryLabel(rowScope)
                    }
                }
            }
        }
    }
}

/**
 * 分组小标题（对应 wear ListHeader）。签名保持一致，调用点只改 import；
 * 内容文字默认套 labelSmall 样式，与 wear 版观感一致。
 */
@Composable
fun ListHeader(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val rowScope = this
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.labelSmall) {
            content(rowScope)
        }
    }
}

/**
 * 小号操作按钮（对应 wear CompactButton）：胶囊形、内边距收窄。
 */
@Composable
fun CompactButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(percent = 50),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)
    ) {
        val rowScope = this
        content(rowScope)
    }
}
