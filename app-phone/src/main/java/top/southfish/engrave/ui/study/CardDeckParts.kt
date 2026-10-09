package top.southfish.engrave.ui.study

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 卡片背后叠的那几张。
 *
 * 下层不是空白卡背，而是队列里下一个词的内容：当前卡被拖走 / 飞出时，
 * 用户能直接看到接下来要背什么。越靠后越小越淡，位置固定不动——
 * 拖动时只有被按住的那张卡自己移动。
 */
@Composable
internal fun DeckBehind(
    faces: List<@Composable () -> Unit>,
    cardWidth: Dp,
    cardHeight: Dp
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
                    alpha = 1f - 0.35f * depth
                }
        ) {
            CardFace(Modifier.fillMaxSize()) { face() }
        }
    }
}
