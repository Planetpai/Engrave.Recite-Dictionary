package top.southfish.engrave.ui.study

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import top.southfish.engrave.domain.model.Word

/** 卡片正面的主文字：单词，或者中文释义。 */
@Composable
internal fun CardTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 卡片背面的释义，比正面小一号。maxLines 由调用方按卡型收紧。 */
@Composable
internal fun CardBody(text: String, maxLines: Int = 4) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 例句和例句翻译。例句只来自词书本身，不查词典。 */
@Composable
internal fun ExampleBlock(
    word: Word,
    exampleMaxLines: Int = 3,
    translationMaxLines: Int = 3
) {
    if (word.example != null || word.exampleTranslation != null) {
        androidx.compose.foundation.layout.Spacer(
            Modifier.padding(top = 4.dp)
        )
    }
    word.example?.let { CardCaption(it, maxLines = exampleMaxLines) }
    word.exampleTranslation?.let { CardCaption(it, maxLines = translationMaxLines) }
}

@Composable
internal fun CardPhonetic(text: String) {
    // ECDICT 音标不带斜杠（如 smɑrt），统一补成 /smɑrt/；已带斜杠的不重复处理
    val display = if (text.isNotBlank() && !text.startsWith("/")) "/$text/" else text
    Text(
        text = display,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
    )
}

@Composable
internal fun CardCaption(text: String, maxLines: Int = 3) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
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
