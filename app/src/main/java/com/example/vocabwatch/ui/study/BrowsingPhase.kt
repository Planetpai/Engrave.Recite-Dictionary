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
internal fun ExampleBlock(
    word: Word,
    exampleMaxLines: Int = 3,
    translationMaxLines: Int = 3
) {
    word.example?.let { CardCaption(it, maxLines = exampleMaxLines) }
    word.exampleTranslation?.let { CardCaption(it, maxLines = translationMaxLines) }
}

/**
 * 浏览选词：先把今天的新词扫一遍。
 *
 * 左滑「已学过」按 EASY 落库，直接排到几天后；上滑「不认识」选进待背列表；
 * 上拉越过阈值卡片停到上方、灰线下面露出例句，再下拉复位继续背这个词。
 */
@Composable
internal fun BrowsingPhase(state: StudyUiState.Browsing, viewModel: StudyViewModel) {
    val word = state.card.item.word
    val parked = state.card.exampleRevealed

    // 顶部让出 TimeText 的位置，否则进度行会和时钟叠字
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        StudyHeader(
            text = "已选 ${state.selectedCount}/${state.selectionTarget} · 待看 ${state.remaining}",
            collapsed = parked
        )

        StudyCardDeck(
            cardKey = word.id,
            behindFaces = state.nextItems.map { item ->
                { BehindWordFace(item.word) }
            },
            flipped = false,
            parked = parked,
            onTap = { if (parked) viewModel.closeReveal() else viewModel.openReveal() },
            onDoubleTap = viewModel::undo,
            onSwipeLeft = { viewModel.answer(Verdict.KNOWN) },
            onSwipeUp = { viewModel.answer(Verdict.UNKNOWN) },
            leftHint = "← 已学过",
            upHint = "↑ 不认识",
            onRevealOpen = viewModel::openReveal,
            onRevealClose = viewModel::closeReveal,
            undoReplay = state.undoReplay,
            revealContent = { ExampleBlock(word, exampleMaxLines = 2, translationMaxLines = 1) },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            front = {
                CardTitle(word.term)
                word.phonetic?.let { CardPhonetic(it) }
                if (!parked) CardCaption("上拉看例句")
            },
            back = null
        )
    }
}
