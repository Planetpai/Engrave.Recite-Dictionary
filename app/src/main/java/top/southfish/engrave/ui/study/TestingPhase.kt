package top.southfish.engrave.ui.study

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/**
 * 正式背单词与复习：点一下翻面，左滑「会」、上滑「不会」，三击撤销。
 *
 * 左滑而不是右滑：Wear 的系统返回手势是从左缘右滑，判定手势和它同向
 * 太容易误触退出。考法跟着刷到的遍数走（状态机决定）：每个词刷满三遍——
 * 第一遍英文面、第二遍中文面、第三遍拼写；答会进入下一遍，答不会归零沉底。
 * 拼写卡没有背面，靠点字母作答。
 */
@Composable
internal fun TestingPhase(state: StudyUiState.Testing, viewModel: StudyViewModel) {
    val card = state.card
    val gradeEnabled = card.flipped && card.kind != CardKind.SPELLING

    // 长按加入错题集后的短暂提示
    val mistakeFlash by viewModel.mistakeFlash.collectAsState()
    var showMistakeHint by remember { mutableStateOf(false) }
    LaunchedEffect(mistakeFlash) {
        if (mistakeFlash > 0L) {
            showMistakeHint = true
            kotlinx.coroutines.delay(1500)
            showMistakeHint = false
        }
    }

    // 顶部让出 TimeText 的位置，否则进度行会和时钟叠字
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        StudyHeader(headerText(state))

        StudyCardDeck(
            cardKey = card.uid,
            behindFaces = state.nextCards.map { next ->
                { BehindTestFace(next) }
            },
            flipped = card.flipped,
            onTap = viewModel::flip,
            onTripleTap = viewModel::undo,
            onSwipeLeft = if (gradeEnabled) {
                { viewModel.answer(Verdict.KNOWN) }
            } else {
                null
            },
            onSwipeUp = if (gradeEnabled) {
                { viewModel.answer(Verdict.UNKNOWN) }
            } else {
                null
            },
            leftHint = "← 会",
            upHint = "↑ 不会",
            undoReplay = state.undoReplay,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            front = { TestFront(card, viewModel) },
            back = { TestBack(card, viewModel) }
        )
    }
}

@Composable
private fun TestFront(card: TestCard, viewModel: StudyViewModel) {
    val word = card.item.word
    when (card.kind) {
        CardKind.EN_TO_ZH -> {
            CardTitle(word.term)
            word.phonetic?.let { CardPhonetic(it) }
            if (!card.flipped) CardCaption("点一下看释义")
        }

        CardKind.ZH_TO_EN -> {
            CardBody(word.meaning)
            if (!card.flipped) CardCaption("点一下看单词")
        }

        CardKind.SPELLING -> {
            // 拼写卡：正面只有释义（空间充裕）；点一下翻到背面做选词填空
            CardBody(word.meaning, maxLines = 3)
            if (!card.flipped) {
                Text(
                    text = "点一下拼写",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TestBack(card: TestCard, viewModel: StudyViewModel) {
    val word = card.item.word
    when (card.kind) {
        CardKind.SPELLING -> {
            // 背面整卡留给选词填空，长词也放得下
            SpellingPanel(
                cardKey = card.uid,
                answer = card.answer,
                scrambled = card.scrambled,
                onResult = { correct ->
                    viewModel.answer(if (correct) Verdict.KNOWN else Verdict.UNKNOWN)
                },
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        CardKind.EN_TO_ZH -> {
            // 背面内容多（释义+例句+译文），全按最大行数会溢出卡片，统一收紧
            CardBody(word.meaning, maxLines = 2)
            ExampleBlock(word, exampleMaxLines = 2, translationMaxLines = 1)
        }

        else -> {
            CardTitle(word.term)
            word.phonetic?.let { CardPhonetic(it) }
            ExampleBlock(word, exampleMaxLines = 2, translationMaxLines = 1)
        }
    }
}

private fun headerText(state: StudyUiState.Testing): String {
    val phaseName = if (state.phase == TestPhase.FORMAL) "背新词" else "复习"
    return "$phaseName · 剩 ${state.remaining}"
}
