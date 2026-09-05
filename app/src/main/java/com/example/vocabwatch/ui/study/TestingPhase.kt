package com.example.vocabwatch.ui.study

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 正式背单词与复习：点一下翻面，左滑「会」、上滑「不会」，双击撤销。
 *
 * 左滑而不是右滑：Wear 的系统返回手势是从左缘右滑，判定手势和它同向
 * 太容易误触退出。三种考法（英选中、中选英、拼写）由状态机按位置轮换，
 * 答错会换一种考法隔两张重来。拼写卡没有背面，靠点字母作答。
 */
@Composable
internal fun TestingPhase(state: StudyUiState.Testing, viewModel: StudyViewModel) {
    val card = state.card
    val gradeEnabled = card.flipped && card.kind != CardKind.SPELLING

    // 顶部让出 TimeText 的位置，否则进度行会和时钟叠字
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        StudyHeader(headerText(state))

        StudyCardDeck(
            cardKey = card.uid,
            behindFaces = state.nextCards.map { next ->
                { BehindTestFace(next) }
            },
            flipped = card.flipped,
            parked = false,
            onTap = viewModel::flip,
            onDoubleTap = viewModel::undo,
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
            back = { TestBack(card) }
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
            CardBody(word.meaning)
            SpellingPanel(
                cardKey = card.uid,
                answer = card.answer,
                scrambled = card.scrambled,
                onResult = { correct ->
                    viewModel.answer(if (correct) Verdict.KNOWN else Verdict.UNKNOWN)
                },
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun TestBack(card: TestCard) {
    val word = card.item.word
    when (card.kind) {
        CardKind.EN_TO_ZH -> {
            CardBody(word.meaning)
            ExampleBlock(word)
        }

        // 拼写卡不会翻面，走不到这里
        else -> {
            CardTitle(word.term)
            word.phonetic?.let { CardPhonetic(it) }
            ExampleBlock(word)
        }
    }
}

private fun headerText(state: StudyUiState.Testing): String {
    val phaseName = if (state.phase == TestPhase.FORMAL) "背新词" else "复习"
    return "$phaseName · 剩 ${state.remaining}"
}
