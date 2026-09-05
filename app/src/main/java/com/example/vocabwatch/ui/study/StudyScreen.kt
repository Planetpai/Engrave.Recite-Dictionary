package com.example.vocabwatch.ui.study

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.vocabwatch.domain.model.Grade
import com.example.vocabwatch.ui.components.formatInterval
import com.example.vocabwatch.ui.components.formatProgress
import com.example.vocabwatch.ui.components.rememberRotaryScrollable
import com.example.vocabwatch.ui.vocabViewModel

/** 四档评分的短标签。手表屏幕放不下「记不住了」这种长词。 */
private val Grade.label: String
    get() = when (this) {
        Grade.AGAIN -> "忘了"
        Grade.HARD -> "模糊"
        Grade.GOOD -> "记得"
        Grade.EASY -> "简单"
    }

private val Grade.tint: Color
    get() = when (this) {
        Grade.AGAIN -> Color(0xFFB00020)
        Grade.HARD -> Color(0xFFB26A00)
        Grade.GOOD -> Color(0xFF1E6FB8)
        Grade.EASY -> Color(0xFF1B7F3B)
    }

@Composable
fun StudyScreen(modifier: Modifier = Modifier) {
    val viewModel = vocabViewModel<StudyViewModel> { container ->
        StudyViewModel(
            getStudySession = container.getStudySession,
            submitReview = container.submitReview,
            settingsRepository = container.settingsRepository,
            scheduler = container.scheduler,
            timeProvider = container.timeProvider
        )
    }
    val state by viewModel.uiState.collectAsState()

    val listState = rememberScalingLazyListState()
    rememberRotaryScrollable(listState)

    Scaffold(
        modifier = modifier,
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState
        ) {
            when (val current = state) {
                StudyUiState.Loading -> item {
                    CenteredText("准备卡片…")
                }

                StudyUiState.Empty -> {
                    item { CenteredText("今天学完了") }
                    item { CenteredText("没有到期或待学的词", MaterialTheme.colors.onSurfaceVariant) }
                    item {
                        Chip(
                            label = { Text("重新加载") },
                            onClick = viewModel::startSession,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                is StudyUiState.Finished -> {
                    item { CenteredText("本轮完成") }
                    item {
                        CenteredText(
                            "复习 ${current.reviewedCount} 张" +
                                if (current.graduatedCount > 0) " · 毕业 ${current.graduatedCount} 张" else ""
                        )
                    }
                    item {
                        Chip(
                            label = { Text("再来一组") },
                            onClick = viewModel::startSession,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                is StudyUiState.Card -> {
                    item {
                        ListHeader {
                            Text(
                                text = formatProgress(current.index + 1, current.total),
                                style = MaterialTheme.typography.caption2,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colors.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    item {
                        Text(
                            text = current.item.word.term,
                            style = MaterialTheme.typography.title1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    current.item.word.phonetic?.let { phonetic ->
                        item {
                            Text(
                                text = phonetic,
                                style = MaterialTheme.typography.caption2,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colors.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (!current.revealed) {
                        item {
                            Button(
                                onClick = viewModel::reveal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                Text("显示释义")
                            }
                        }
                    } else {
                        item {
                            Text(
                                text = current.item.word.meaning,
                                style = MaterialTheme.typography.body1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                            )
                        }

                        current.item.word.example?.let { example ->
                            item {
                                Text(
                                    text = example,
                                    style = MaterialTheme.typography.caption2,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colors.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp)
                                )
                            }
                        }

                        current.item.word.exampleTranslation?.let { translation ->
                            item {
                                Text(
                                    text = translation,
                                    style = MaterialTheme.typography.caption2,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colors.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // 评分选项。secondaryLabel 显示这档评分对应的下次复习间隔，
                        // 让用户在点之前就知道选「记得」会变成几天后
                        Grade.values().forEach { grade ->
                            item {
                                Chip(
                                    label = { Text(grade.label) },
                                    secondaryLabel = {
                                        val days = current.intervalPreviews[grade]
                                        Text(if (days == null) "" else formatInterval(days))
                                    },
                                    onClick = { viewModel.grade(grade) },
                                    colors = ChipDefaults.primaryChipColors(
                                        backgroundColor = grade.tint
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredText(
    text: String,
    color: Color = MaterialTheme.colors.onSurface
) {
    Text(
        text = text,
        style = MaterialTheme.typography.body1,
        textAlign = TextAlign.Center,
        color = color,
        modifier = Modifier.fillMaxWidth()
    )
}
