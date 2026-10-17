package top.southfish.engrave.ui.study

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import androidx.compose.material3.MaterialTheme
import top.southfish.engrave.ui.rememberAppContainer

/**
 * 浏览选词：先把今天的新词扫一遍。
 *
 * 卡片正面英文+例句、背面中文释义+例句翻译，单击可翻面预览；判定滑动
 * （左滑已学过 / 上滑不认识）不要求先翻面，正反面都能直接滑。
 * 左滑「已学过」按 EASY 落库，直接排到几天后；上滑「不认识」选进待背列表。
 */
@Composable
internal fun BrowsingPhase(state: StudyUiState.Browsing, viewModel: StudyViewModel) {
    val word = state.card.item.word

    // 翻面只是预览，跟着词走；换卡自动回到正面
    var flipped by remember(word.id) { mutableStateOf(false) }

    val mistakeFlash by viewModel.mistakeFlash.collectAsState()
    var showMistakeHint by remember { mutableStateOf(false) }
    LaunchedEffect(mistakeFlash) {
        if (mistakeFlash > 0L) {
            showMistakeHint = true
            kotlinx.coroutines.delay(1500)
            showMistakeHint = false
        }
    }

    // 卡片自动发音：设置开启时，英文词头一出现就朗读；翻面预览不重读，换卡才触发
    val ttsEnabled by viewModel.ttsEnabled.collectAsState()
    val ttsSpeaker = rememberAppContainer().ttsSpeaker
    LaunchedEffect(word.id, ttsEnabled) {
        if (ttsEnabled) ttsSpeaker.speak(word.term)
    }

    // 顶部让出 TimeText 的位置，否则进度行会和时钟叠字
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        StudyHeader(
            text = "已选 ${state.selectedCount}/${state.selectionTarget}"
        )

        if (showMistakeHint) {
            Text(
                text = "✓ 已加入错题集",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        StudyCardDeck(
            cardKey = word.id,
            behindFaces = state.nextItems.map { item ->
                { BehindWordFace(item.word) }
            },
            flipped = flipped,
            onTap = { flipped = !flipped },
            onUndo = viewModel::undo,
            onLongPress = viewModel::addCurrentToMistakes,
            onSwipeLeft = { viewModel.answer(Verdict.KNOWN) },
            onSwipeUp = { viewModel.answer(Verdict.UNKNOWN) },
            undoReplay = state.undoReplay,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            front = {
                CardTitle(word.term)
                word.phonetic?.let { CardPhonetic(it) }
                // 例句跟单词同面：见词即见语境
                word.example?.let { CardCaption(it, maxLines = 2) }
            },
            back = {
                // 背面：中文释义 + 例句翻译；圆屏空间有限，长文靠省略号收口
                CardBody(word.meaning, maxLines = 2)
                word.exampleTranslation?.let { CardCaption(it, maxLines = 2) }
            }
        )
    }
}

