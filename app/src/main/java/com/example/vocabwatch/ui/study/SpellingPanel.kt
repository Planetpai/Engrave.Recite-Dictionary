package com.example.vocabwatch.ui.study

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
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/** 一行放几个字母。手表宽度只够 7 个 20dp 的格子。 */
private const val LETTERS_PER_ROW = 7

/**
 * 拼写卡：中文提示下面给一堆乱序字母，点字母填进槽位，填满自动判定。
 * 点已填的槽位可以把字母退回去，拼错会抖一下再清空重拼。
 *
 * 判定结果通过 [onResult] 交回状态机，对错分别走「会 / 不会」两条路。
 */
@Composable
fun SpellingPanel(
    cardKey: Any,
    answer: List<Char>,
    scrambled: List<Char>,
    onResult: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val typed = remember(cardKey) { mutableStateListOf<Int>() }
    var wrong by remember(cardKey) { mutableStateOf(false) }
    val shake = remember(cardKey) { Animatable(0f) }

    LaunchedEffect(typed.size) {
        if (answer.isNotEmpty() && typed.size == answer.size) {
            if (typed.map { scrambled[it] } == answer) {
                onResult(true)
            } else {
                wrong = true
                repeat(3) {
                    shake.animateTo(7f, tween(45))
                    shake.animateTo(-7f, tween(45))
                }
                shake.animateTo(0f, tween(45))
                typed.clear()
                wrong = false
                // 拼错不是免费重试：按「不会」落库并重排，隔几张再来
                onResult(false)
            }
        }
    }

    Column(
        modifier = modifier.graphicsLayer { translationX = shake.value },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)
    ) {
        answer.indices.chunked(LETTERS_PER_ROW).forEach { slots ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                slots.forEach { slot ->
                    val source = if (slot < typed.size) typed[slot] else null
                    LetterBox(
                        letter = if (source == null) null else scrambled[source],
                        highlighted = wrong,
                        onClick = if (source == null) null else {
                            { typed.removeAt(slot) }
                        }
                    )
                }
            }
        }

        scrambled.indices.chunked(LETTERS_PER_ROW).forEach { sources ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                sources.forEach { source ->
                    LetterBox(
                        letter = scrambled[source],
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
private fun LetterBox(
    letter: Char?,
    modifier: Modifier = Modifier,
    used: Boolean = false,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .size(20.dp)
            .alpha(if (used) 0.25f else 1f)
            .clip(shape)
            .background(if (highlighted) Color(0xFF5A2028) else HintBackgroundColor)
            .then(if (letter == null) Modifier.border(1.dp, CardBorderColor, shape) else Modifier)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        contentAlignment = Alignment.Center
    ) {
        if (letter != null) {
            Text(
                text = letter.toString(),
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurface
            )
        }
    }
}
