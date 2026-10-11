package top.southfish.engrave.ui.tutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.delay
import top.southfish.engrave.ui.rememberAppContainer
import top.southfish.engrave.ui.study.CardBody
import top.southfish.engrave.ui.study.CardCaption
import top.southfish.engrave.ui.study.CardPhonetic
import top.southfish.engrave.ui.study.CardTitle
import top.southfish.engrave.ui.study.ExampleBlock
import top.southfish.engrave.ui.study.SpellingPanel
import top.southfish.engrave.ui.study.StudyCardDeck
import top.southfish.engrave.ui.study.SwipeDirection

/**
 * 交互式使用指引：用户在真实卡片上亲手把核心手势做一遍。
 *
 * 纯演示，不碰数据库 / ViewModel：演示词与文案来自 core 的 [TutorialDemo]/
 * [TutorialStep]，卡片与拼写直接复用学习页组件（手感与真实学习一致）。
 * 引导方式 = 约束式：当前步骤只开放目标手势，其余传 null 禁用，用户做不错。
 * 每步做对了出 ✓ 反馈，约 0.8s 后自动进下一步；底部常驻「跳过教程」。
 */
@Composable
fun TutorialScreen(
    onDone: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val step = TutorialStep.entries[stepIndex]

    // 当前步已完成：展示反馈文案，LaunchedEffect 延时后自动切下一步
    var doneFeedback by remember(stepIndex) { mutableStateOf<String?>(null) }
    LaunchedEffect(doneFeedback) {
        if (doneFeedback != null) {
            delay(800)
            doneFeedback = null
            stepIndex++
        }
    }

    // 撤销步的状态提到外层：副文案要随「卡已滑走」切换成双击引导
    var undoSwipedDir by remember(stepIndex) { mutableStateOf<SwipeDirection?>(null) }
    var undoCardGen by remember(stepIndex) { mutableIntStateOf(0) }
    var undoReplay by remember(stepIndex) { mutableStateOf<SwipeDirection?>(null) }

    Scaffold(
        modifier = modifier,
        timeText = { TimeText() }
    ) {
        if (step == TutorialStep.DONE) {
            DonePage(onDone = onDone)
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${stepIndex + 1}/${TutorialStep.ACTION_COUNT} · ${step.title}",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = if (step == TutorialStep.UNDO && undoSwipedDir != null) {
                    "现在双击卡片外的空白处"
                } else {
                    step.subtitle
                },
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                when (step) {
                    TutorialStep.FLIP -> FlipStep(onComplete = { doneFeedback = it })
                    TutorialStep.SWIPE_LEFT -> SwipeStep(
                        word = TutorialDemo.apple,
                        direction = SwipeDirection.LEFT,
                        onComplete = { doneFeedback = it }
                    )

                    TutorialStep.SWIPE_UP -> SwipeStep(
                        word = TutorialDemo.banana,
                        direction = SwipeDirection.UP,
                        onComplete = { doneFeedback = it }
                    )

                    TutorialStep.SPELLING -> SpellingStep(onComplete = { doneFeedback = it })
                    TutorialStep.LONG_PRESS -> LongPressStep(onComplete = { doneFeedback = it })
                    TutorialStep.UNDO -> StudyCardDeck(
                        cardKey = "t-undo-$undoCardGen",
                        behindFaces = emptyList(),
                        flipped = false,
                        onTap = {},
                        // 卡滑走后才开放双击撤销，防止误触
                        onUndo = if (undoSwipedDir != null) {
                            {
                                undoReplay = undoSwipedDir
                                undoSwipedDir = null
                                // 换 cardKey 重新组合卡片，undoReplay 让它从飞出方向飞回
                                undoCardGen++
                                doneFeedback = "✓ 撤回来了"
                            }
                        } else {
                            null
                        },
                        onSwipeLeft = if (undoSwipedDir == null) {
                            { undoSwipedDir = SwipeDirection.LEFT }
                        } else {
                            null
                        },
                        onSwipeUp = if (undoSwipedDir == null) {
                            { undoSwipedDir = SwipeDirection.UP }
                        } else {
                            null
                        },
                        undoReplay = undoReplay,
                        modifier = Modifier.fillMaxSize(),
                        front = {
                            CardTitle(TutorialDemo.banana.term)
                            TutorialDemo.banana.phonetic?.let { CardPhonetic(it) }
                            CardCaption("先把我滑走")
                        }
                    )

                    TutorialStep.SEARCH -> SearchStep(onComplete = { doneFeedback = it })
                    TutorialStep.DETAIL -> DetailStep(onComplete = { doneFeedback = it })
                    TutorialStep.DONE -> Unit // 已在上面整页接管
                }
            }

            doneFeedback?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                text = "跳过教程",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { stepIndex = TutorialStep.DONE.ordinal }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/** 第 1 步：点卡片翻面。 */
@Composable
private fun FlipStep(onComplete: (String) -> Unit) {
    var flipped by remember { mutableStateOf(false) }
    val word = TutorialDemo.apple
    StudyCardDeck(
        cardKey = "t-flip",
        behindFaces = emptyList(),
        flipped = flipped,
        onTap = {
            flipped = !flipped
            if (flipped) onComplete("✓ 很好")
        },
        onSwipeLeft = null,
        onSwipeUp = null,
        modifier = Modifier.fillMaxSize(),
        front = {
            CardTitle(word.term)
            word.phonetic?.let { CardPhonetic(it) }
            if (!flipped) CardCaption("点一下看释义")
        },
        back = {
            CardBody(word.meaning, maxLines = 2)
            ExampleBlock(word, exampleMaxLines = 2, translationMaxLines = 1)
        }
    )
}

/** 第 2/3 步：翻面后向左 / 向上滑。未翻面时滑动禁用（与真实学习一致）。 */
@Composable
private fun SwipeStep(
    word: top.southfish.engrave.domain.model.Word,
    direction: SwipeDirection,
    onComplete: (String) -> Unit
) {
    var flipped by remember { mutableStateOf(false) }
    val swipeLeft = if (flipped && direction == SwipeDirection.LEFT) ({ onComplete("✓ 很好") }) else null
    val swipeUp = if (flipped && direction == SwipeDirection.UP) ({ onComplete("✓ 很好") }) else null
    StudyCardDeck(
        cardKey = "t-swipe-${word.term}-$direction",
        behindFaces = emptyList(),
        flipped = flipped,
        onTap = { flipped = !flipped },
        onSwipeLeft = swipeLeft,
        onSwipeUp = swipeUp,
        modifier = Modifier.fillMaxSize(),
        front = {
            CardTitle(word.term)
            word.phonetic?.let { CardPhonetic(it) }
            if (!flipped) CardCaption("点一下看释义")
        },
        back = {
            CardBody(word.meaning, maxLines = 2)
            ExampleBlock(word, exampleMaxLines = 2, translationMaxLines = 1)
        }
    )
}

/** 第 4 步：拼写卡，拼对即完成（拼错走面板自带的抖动/改错，不计失败）。 */
@Composable
private fun SpellingStep(onComplete: (String) -> Unit) {
    var flipped by remember { mutableStateOf(false) }
    StudyCardDeck(
        cardKey = "t-spell",
        behindFaces = emptyList(),
        flipped = flipped,
        onTap = { flipped = !flipped },
        onSwipeLeft = null,
        onSwipeUp = null,
        modifier = Modifier.fillMaxSize(),
        front = {
            CardBody(TutorialDemo.apple.meaning, maxLines = 3)
            if (!flipped) CardCaption("点一下拼写")
        },
        back = {
            SpellingPanel(
                cardKey = "t-spell",
                answer = TutorialDemo.spellAnswer,
                scrambled = TutorialDemo.spellScrambled,
                onResult = { correct -> if (correct) onComplete("✓ 拼对了") }
            )
        }
    )
}

/** 第 5 步：长按卡片加入错题集（仅演示提示，不落库）。 */
@Composable
private fun LongPressStep(onComplete: (String) -> Unit) {
    StudyCardDeck(
        cardKey = "t-long",
        behindFaces = emptyList(),
        flipped = false,
        onTap = {},
        onLongPress = { onComplete("✓ 已加入错题集（演示）") },
        onSwipeLeft = null,
        onSwipeUp = null,
        modifier = Modifier.fillMaxSize(),
        front = {
            CardTitle(TutorialDemo.apple.term)
            TutorialDemo.apple.phonetic?.let { CardPhonetic(it) }
            CardCaption("长按试试")
        }
    )
}

/** 第 7 步：仿真搜索结果，左右滑切换单词 / 词组两列，点条目即完成。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SearchStep(onComplete: (String) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        // 仿真搜索框（静态）
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colors.surface)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "apple",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface
            )
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (page == 0) {
                    TutorialDemo.searchWords.forEach { (term, phonetic) ->
                        FakeResultItem(term, phonetic) { onComplete("✓ 很好") }
                    }
                } else {
                    TutorialDemo.searchPhrases.forEach { (phrase, zh) ->
                        FakeResultItem(phrase, zh) { onComplete("✓ 很好") }
                    }
                }
            }
        }
        // 页指示点
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(2) { index ->
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(
                            if (pagerState.currentPage == index) {
                                MaterialTheme.colors.primary
                            } else {
                                MaterialTheme.colors.onSurfaceVariant
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun FakeResultItem(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.body1, color = MaterialTheme.colors.onSurface)
        Text(subtitle, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
    }
}

/** 第 8 步：仿真详情页，点顶部标题真实发音。 */
@Composable
private fun DetailStep(onComplete: (String) -> Unit) {
    val container = rememberAppContainer()
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = TutorialDemo.apple.term,
            style = MaterialTheme.typography.title1,
            color = MaterialTheme.colors.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    container.ttsSpeaker.speak(TutorialDemo.apple.term)
                    onComplete("✓ 很好")
                }
                .padding(horizontal = 12.dp, vertical = 2.dp)
        )
        TutorialDemo.apple.phonetic?.let { CardPhonetic(it) }
        CardBody(TutorialDemo.apple.meaning, maxLines = 2)
        Text(
            text = "词形 复数 apples",
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = "词组 apple pie 苹果派",
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** 完成页：手势速查（保留原静态版使用指引的全部信息）+ 完成按钮。 */
@Composable
private fun DonePage(onDone: () -> Unit) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                text = "✓ 教程完成",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Text(
                text = "随时可从「我的 → 使用指引」再来练习",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            )
        }
        items(TutorialDemo.cheatsheet) { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }
        item {
            Button(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 8.dp)
            ) { Text("完成") }
        }
    }
}
