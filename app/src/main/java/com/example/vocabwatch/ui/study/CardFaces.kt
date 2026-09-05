package com.example.vocabwatch.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/** AMOLED 省电：卡片底色贴近纯黑，只靠一道暗边框和叠卡阴影分出层次。 */
internal val CardShape = RoundedCornerShape(22.dp)
internal val CardSurfaceColor = Color(0xFF14171C)
internal val CardBorderColor = Color(0xFF313842)
internal val DeckEdgeColor = Color(0xFF1D222A)
internal val HintBackgroundColor = Color(0xFF252B34)
internal val HintTextColor = Color(0xFFB9C4D0)

/** 停驻露例句时，卡片和例句之间那条灰线 */
internal val RevealDividerColor = Color(0xFF3A414B)

/** 一张卡片的表面：圆角、暗底、内容居中。正反面共用。 */
@Composable
internal fun CardFace(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(CardSurfaceColor)
            .border(1.dp, CardBorderColor, CardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content
    )
}

/** 手势引导标识，贴在卡片边缘告诉用户往哪滑。 */
@Composable
internal fun HintBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption2,
        color = HintTextColor,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(HintBackgroundColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
