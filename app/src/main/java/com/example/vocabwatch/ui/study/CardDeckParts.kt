package com.example.vocabwatch.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
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

/**
 * 卡片背后叠的那几张。
 *
 * 下层不是空白卡背，而是队列里下一个词的内容：上拉或滑走当前卡时，
 * 用户能直接看到接下来要背什么。越靠后越小越淡。
 */
@Composable
internal fun DeckBehind(
    faces: List<@Composable () -> Unit>,
    cardWidth: Dp,
    cardHeight: Dp,
    fade: () -> Float
) {
    val stepPx = with(LocalDensity.current) { 7.dp.toPx() }
    faces.forEachIndexed { index, face ->
        val depth = index + 1
        Box(
            Modifier
                .size(cardWidth, cardHeight)
                .graphicsLayer {
                    scaleX = 1f - 0.05f * depth
                    scaleY = 1f - 0.05f * depth
                    translationY = stepPx * depth
                    // 例句展开时下层淡出，把屏幕中段让给例句
                    alpha = (1f - 0.35f * depth) * (1f - fade())
                }
        ) {
            CardFace(Modifier.fillMaxSize()) { face() }
        }
    }
}

/** 手势引导标识，贴在卡片内缘告诉用户往哪滑，拖得越多越亮。 */
@Composable
internal fun BoxScope.CardHintOverlay(
    leftHint: String?,
    upHint: String?,
    leftProgress: () -> Float,
    upProgress: () -> Float
) {
    if (leftHint != null) {
        HintBadge(
            text = leftHint,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, bottom = 6.dp)
                .graphicsLayer { alpha = (0.4f + leftProgress() * 0.6f).coerceIn(0.4f, 1f) }
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
