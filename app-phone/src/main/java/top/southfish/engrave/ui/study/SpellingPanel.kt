package top.southfish.engrave.ui.study

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

/** 一行放几个分块格子：逐字母 6 个一行，分块 5 个一行，都刚好塞进卡内宽 */
private const val CHUNKS_PER_ROW = 5
private const val LETTERS_PER_ROW = 6

/** 格子尺寸。高度压到 16dp：行数多时（长词两行槽+两行待选）也放得下 */
private val CHUNK_WIDTH_CHUNKS = 24.dp
private val CHUNK_WIDTH_LETTERS = 20.dp
private val CHUNK_HEIGHT = 16.dp

/**
 * 拼写卡：中文提示下面给一堆乱序分块，点块填进槽位，填满自动判定。
 * 点已填的槽位可以把块退回去，拼错会抖一下再清空重拼。
 *
 * 判定结果通过 [onResult] 交回状态机，对错分别走「会 / 不会」两条路。
 */
@Composable
fun SpellingPanel(
    cardKey: Any,
    answer: List<String>,
    scrambled: List<String>,
    onResult: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val typed = remember(cardKey) { mutableStateListOf<Int>() }
    var wrong by remember(cardKey) { mutableStateOf(false) }
    /** 拼错槽位的下标：第一次拼错后持续高亮，指导就地改错 */
    val markedWrong = remember(cardKey) { mutableStateListOf<Int>() }
    var failCount by remember(cardKey) { mutableStateOf(0) }
    val shake = remember(cardKey) { Animatable(0f) }

    // 逐字母（短词）一行能放 6 个，分块（长词）一行 5 个
    val singleLetters = answer.all { it.length == 1 }
    val perRow = if (singleLetters) LETTERS_PER_ROW else CHUNKS_PER_ROW
    val tileWidth = if (singleLetters) CHUNK_WIDTH_LETTERS else CHUNK_WIDTH_CHUNKS

    LaunchedEffect(typed.size) {
        if (answer.isEmpty() || typed.size != answer.size) return@LaunchedEffect

        val wrongSlots = typed.indices.filter { scrambled[typed[it]] != answer[it] }
        if (wrongSlots.isEmpty()) {
            // 改对了：清除标记，按「会」处理
            markedWrong.clear()
            failCount = 0
            onResult(true)
            return@LaunchedEffect
        }

        wrong = true
        repeat(3) {
            shake.animateTo(7f, tween(45))
            shake.animateTo(-7f, tween(45))
        }
        shake.animateTo(0f, tween(45))
        wrong = false

        if (failCount == 0) {
            // 第一次拼错：高亮填错的槽位、已填的保留，就地改错重试
            failCount = 1
            markedWrong.clear()
            markedWrong.addAll(wrongSlots)
        } else {
            // 第二次拼错：清空重来，按「不会」落库并沉底
            failCount = 0
            markedWrong.clear()
            typed.clear()
            onResult(false)
        }
    }

    Column(
        modifier = modifier.graphicsLayer { translationX = shake.value },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        answer.indices.chunked(perRow).forEach { slots ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                slots.forEach { slot ->
                    val source = if (slot < typed.size) typed[slot] else null
                    ChunkBox(
                        chunk = if (source == null) null else scrambled[source],
                        width = tileWidth,
                        highlighted = wrong || markedWrong.contains(slot),
                        onClick = if (source == null) null else {
                            { typed.removeAt(slot) }
                        }
                    )
                }
            }
        }

        scrambled.indices.chunked(perRow).forEach { sources ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                sources.forEach { source ->
                    ChunkBox(
                        chunk = scrambled[source],
                        width = tileWidth,
                        used = typed.contains(source),
                        onClick = if (typed.contains(source) || typed.size >= answer.size) null else {
                            { typed.add(source) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChunkBox(
    chunk: String?,
    width: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    used: Boolean = false,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .size(width = width, height = CHUNK_HEIGHT)
            .alpha(if (used) 0.25f else 1f)
            .clip(shape)
            .background(if (highlighted) Color(0xFF5A2028) else HintBackgroundColor)
            .then(if (chunk == null) Modifier.border(1.dp, CardBorderColor, shape) else Modifier)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        contentAlignment = Alignment.Center
    ) {
        if (chunk != null) {
            Text(
                text = chunk,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
