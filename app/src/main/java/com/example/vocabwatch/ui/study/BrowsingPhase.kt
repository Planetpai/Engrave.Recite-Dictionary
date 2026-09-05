package com.example.vocabwatch.ui.study

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vocabwatch.domain.model.Word

/** 例句和例句翻译。例句只来自词书本身，不查词典。 */
@Composable
internal fun ExampleBlock(word: Word) {
    word.example?.let { CardCaption(it) }
    word.exampleTranslation?.let { CardCaption(it) }
}

/**
 * 浏览选词：先把今天的新词扫一遍。
 *
 * 右滑「已学过」按 EASY 落库，直接排到几天后；上滑「不认识」选进待背列表；
 * 上拉（或点一下）露出例句，露出后就一直显示，方便边看边决定。
 */
@Composable
internal fun BrowsingPhase(state: StudyUiState.Browsing, viewModel: StudyViewModel) {
    val word = state.card.item.word
    val revealed = state.card.exampleRevealed

    // 顶部让出 TimeText 的位置，否则进度行会和时钟叠字
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        StudyHeader("已选 ${state.selectedCount}/${state.selectionTarget} · 待看 ${state.remaining}")

        StudyCardDeck(
            cardKey = word.id,
            behindCount = state.behindCount,
            flipped = false,
            onTap = viewModel::revealExample,
            onDoubleTap = viewModel::undo,
            onSwipeRight = { viewModel.answer(Verdict.KNOWN) },
            onSwipeUp = { viewModel.answer(Verdict.UNKNOWN) },
            rightHint = "已学过 →",
            upHint = "↑ 不认识",
            onPullReveal = viewModel::revealExample,
            undoReplay = state.undoReplay,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            front = {
                CardTitle(word.term)
                word.phonetic?.let { CardPhonetic(it) }
                if (revealed) {
                    ExampleBlock(word)
                } else {
                    CardCaption("上拉看例句")
                }
            },
            back = null
        )
    }
}
