package com.example.vocabwatch.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 卡片背后叠的那几张，越靠后越小越淡，营造一叠卡的感觉。 */
@Composable
internal fun DeckBehind(behindCount: Int, cardWidth: Dp, cardHeight: Dp) {
    // graphicsLayer 的接收者自带一个 Float 型 density，别和 LocalDensity 的对象混用
    val stepPx = with(LocalDensity.current) { 7.dp.toPx() }
    repeat(behindCount) { index ->
        val depth = index + 1
        Box(
            Modifier
                .size(cardWidth, cardHeight)
                .graphicsLayer {
                    scaleX = 1f - 0.05f * depth
                    scaleY = 1f - 0.05f * depth
                    translationY = stepPx * depth
                    alpha = 1f - 0.3f * depth
                }
                .clip(CardShape)
                .background(DeckEdgeColor)
        )
    }
}

/** 两个方向的手势引导标识，拖得越多越亮。 */
@Composable
internal fun BoxScope.CardHintOverlay(
    rightHint: String?,
    upHint: String?,
    rightProgress: () -> Float,
    upProgress: () -> Float
) {
    if (rightHint != null) {
        HintBadge(
            text = rightHint,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .graphicsLayer { alpha = (0.4f + rightProgress() * 0.6f).coerceIn(0.4f, 1f) }
        )
    }
    if (upHint != null) {
        HintBadge(
            text = upHint,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 2.dp)
                .graphicsLayer { alpha = (0.4f + upProgress() * 0.6f).coerceIn(0.4f, 1f) }
        )
    }
}
