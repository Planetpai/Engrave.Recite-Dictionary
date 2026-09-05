package com.example.vocabwatch.ui.study

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.vocabwatch.domain.model.Word

/** 卡片正面的主文字：单词，或者中文释义。 */
@Composable
internal fun CardTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.title2,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 卡片背面的释义，比正面小一号，给例句留地方。 */
@Composable
internal fun CardBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.body2,
        textAlign = TextAlign.Center,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun CardPhonetic(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption2,
        color = MaterialTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
    )
}

@Composable
internal fun CardCaption(text: String, maxLines: Int = 3) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption2,
        color = MaterialTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    )
}

/** 叠卡下层：下一个词的单词 + 音标，上拉当前卡时能直接看到 */
@Composable
internal fun BehindWordFace(word: Word) {
    CardTitle(word.term)
    word.phonetic?.let { CardPhonetic(it) }
}

/** 叠卡下层：下一张卡的提示面，只看不答 */
@Composable
internal fun BehindTestFace(card: TestCard) {
    val word = card.item.word
    if (card.kind == CardKind.EN_TO_ZH) {
        CardTitle(word.term)
        word.phonetic?.let { CardPhonetic(it) }
    } else {
        CardBody(word.meaning)
    }
}
