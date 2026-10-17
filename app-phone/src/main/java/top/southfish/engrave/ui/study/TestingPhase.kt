package top.southfish.engrave.ui.study

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.southfish.engrave.ui.rememberAppContainer

/**
 * 正式背单词与复习：点一下翻面，左滑「会」、上滑「不会」，双击卡片外区域撤销。
 * 背新词第一遍（英→中）例外：正面不翻面也能直接左滑/上滑给分。
 *
 * 左滑而不是右滑：Wear 的系统返回手势是从左缘右滑，判定手势和它同向
 * 太容易误触退出。考法跟着刷到的遍数走（状态机决定）：每个词刷满三遍——
 * 第一遍英文面、第二遍中文面、第三遍拼写；答会进入下一遍，答不会归零沉底。
 * 拼写卡没有背面，靠点字母作答。
 *
 * 例句只挂在「背新词」第一遍（看英背中）的卡上：正面词+例句、背面释义+
 * 例句翻译；第二三遍和复习卡完全不动，避免每次复习都要多读一段。
 */
@Composable
internal fun TestingPhase(state: StudyUiState.Testing, viewModel: StudyViewModel) {
    val card = state.card
    // 背新词的第一遍（英→中）：例句挪到正面，背面只放翻译
    val showExample = state.phase == TestPhase.FORMAL &&
        card.kind == CardKind.EN_TO_ZH &&
        card.encounter == 1
    // 第一遍（英→中）正面即可直接滑「会/不会」，不强制翻面；其余卡型仍翻面后才给分
    val gradeEnabled = card.kind != CardKind.SPELLING && (card.flipped || showExample)

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

    // 卡片自动发音：英→中卡正面露出词头时朗读；中→英卡翻到背面露出词头时
    // 朗读；拼写卡不能出声（等于听写作弊）。一张卡只读一次——第一遍可反复
    // 切正反面，切回正面不重读。
    val ttsEnabled by viewModel.ttsEnabled.collectAsState()
    val ttsSpeaker = rememberAppContainer().ttsSpeaker
    var spoken by remember(card.uid) { mutableStateOf(false) }
    LaunchedEffect(card.uid, card.flipped, ttsEnabled) {
        val shouldSpeak = when {
            !ttsEnabled -> false
            spoken -> false
            card.kind == CardKind.EN_TO_ZH && !card.flipped -> true
            card.kind == CardKind.ZH_TO_EN && card.flipped -> true
            else -> false
        }
        if (shouldSpeak) {
            ttsSpeaker.speak(card.item.word.term)
            spoken = true
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
            onUndo = viewModel::undo,
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
            undoReplay = state.undoReplay,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            front = { TestFront(card, showExample) },
            back = { TestBack(card, showExample, viewModel) }
        )
    }
}

@Composable
private fun TestFront(card: TestCard, showExample: Boolean) {
    val word = card.item.word
    when (card.kind) {
        CardKind.EN_TO_ZH -> {
            CardTitle(word.term)
            word.phonetic?.let { CardPhonetic(it) }
            // 背新词第一遍：例句跟单词同面，见词即见语境
            if (showExample) word.example?.let { CardCaption(it, maxLines = 2) }
        }

        CardKind.ZH_TO_EN -> CardBody(word.meaning)

        CardKind.SPELLING ->
            // 拼写卡：正面只有释义（空间充裕）；点一下翻到背面做选词填空
            CardBody(word.meaning, maxLines = 3)
    }
}

@Composable
private fun TestBack(card: TestCard, showExample: Boolean, viewModel: StudyViewModel) {
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
            if (showExample) {
                // 背新词第一遍：例句已放正面，背面只补例句翻译
                CardBody(word.meaning, maxLines = 2)
                word.exampleTranslation?.let { CardCaption(it, maxLines = 2) }
            } else {
                // 背面内容多（释义+例句+译文），全按最大行数会溢出卡片，统一收紧
                CardBody(word.meaning, maxLines = 2)
                ExampleBlock(word, exampleMaxLines = 2, translationMaxLines = 1)
            }
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
