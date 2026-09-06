package com.example.vocabwatch.ui.mistakes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.vocabwatch.ui.study.CardBody
import com.example.vocabwatch.ui.study.CardPhonetic
import com.example.vocabwatch.ui.study.CardTitle
import com.example.vocabwatch.ui.study.StudyCardDeck
import com.example.vocabwatch.ui.study.StudyHeader
import com.example.vocabwatch.ui.vocabViewModel

/**
 * 错题本「随刷随学」：跟背单词同一套卡片交互，但只过一轮
 * 正面英文 → 背面中文；上滑不认识的词沉底循环，全部认识才结束。
 * 每次进入顺序重新洗牌，进度不保存。
 */
@Composable
fun MistakesDrillScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = vocabViewModel<MistakesDrillViewModel> { container ->
        MistakesDrillViewModel(
            mistakeDao = container.mistakeDao,
            dictRepository = container.dictRepository,
            studyRepository = container.studyRepository
        )
    }
    val state by viewModel.uiState.collectAsState()

    Scaffold(modifier = modifier, timeText = { TimeText() }) {
        Box(Modifier.fillMaxSize()) {
            when (val current = state) {
                DrillUiState.Loading -> DrillMessage("准备错题卡…")

                DrillUiState.Empty -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
                ) {
                    DrillMessage("错题集是空的")
                    DrillMessage("先去搜索页加点错题吧", MaterialTheme.colors.onSurfaceVariant)
                    androidx.wear.compose.material.Button(
                        onClick = onExit,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("好的") }
                }

                is DrillUiState.Drill -> DrillPhase(current, viewModel)

                is DrillUiState.Finished -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
                ) {
                    DrillMessage("刷完一轮！")
                    DrillMessage(
                        text = "认识 ${current.knownCount} 个词" +
                            if (current.lapsedCount > 0) {
                                " · 不熟 ${current.lapsedCount} 个，已排到明天复习"
                            } else {
                                ""
                            },
                        color = MaterialTheme.colors.onSurfaceVariant
                    )
                    androidx.wear.compose.material.Button(
                        onClick = viewModel::reload,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("再来一轮") }
                    androidx.wear.compose.material.Button(
                        onClick = onExit,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("退出") }
                }
            }
        }
    }
}

@Composable
private fun DrillPhase(state: DrillUiState.Drill, viewModel: MistakesDrillViewModel) {
    val card = state.card
    val gradeEnabled = state.flipped

    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        StudyHeader(text = "随刷随学 · 剩 ${state.remaining}")

        StudyCardDeck(
            cardKey = state.seq,
            behindFaces = emptyList(),
            flipped = state.flipped,
            parked = false,
            onTap = viewModel::flip,
            onDoubleTap = { },
            onSwipeLeft = if (gradeEnabled) {
                { viewModel.answer(true) }
            } else {
                null
            },
            onSwipeUp = if (gradeEnabled) {
                { viewModel.answer(false) }
            } else {
                null
            },
            leftHint = "← 认识",
            upHint = "↑ 不认识",
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            front = {
                CardTitle(card.term)
                card.phonetic?.let { CardPhonetic(it) }
                if (!state.flipped) {
                    Text(
                        text = "点一下看释义",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            back = {
                CardBody(card.meaning ?: "（没有释义）")
            }
        )
    }
}

@Composable
private fun DrillMessage(text: String, color: Color = MaterialTheme.colors.onSurface) {
    Text(
        text = text,
        style = MaterialTheme.typography.body1,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}
