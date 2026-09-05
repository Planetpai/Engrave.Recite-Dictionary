package com.example.vocabwatch.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.vocabwatch.ui.components.formatProgress
import com.example.vocabwatch.ui.components.rememberRotaryScrollable
import com.example.vocabwatch.ui.vocabViewModel

@Composable
fun HomeScreen(
    onStartStudy: () -> Unit,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = vocabViewModel<HomeViewModel> { container ->
        HomeViewModel(
            studyRepository = container.studyRepository,
            settingsRepository = container.settingsRepository,
            wordRepository = container.wordRepository
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
            item {
                ListHeader {
                    Text(
                        text = state.activeBookName ?: "全部词书",
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Text(
                    text = if (state.isLoading) {
                        "准备词书…"
                    } else {
                        formatProgress(state.stats.learnedWords, state.stats.totalWords)
                    },
                    style = MaterialTheme.typography.display1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Text(
                    text = summaryLine(state),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Button(
                    onClick = onStartStudy,
                    enabled = !state.isLoading && state.hasAnythingToStudy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(
                        if (state.sessionSize > 0) "开始学习 ${state.sessionSize}" else "开始学习"
                    )
                }
            }

            item {
                Chip(
                    label = { Text("学习统计") },
                    onClick = onOpenStats,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (state.stats.streakDays > 0) {
                item {
                    Text(
                        text = "已连续学习 ${state.stats.streakDays} 天",
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/** 首页那行小字，把「该复习多少、还能学多少新词」压成一句话。 */
private fun summaryLine(state: HomeUiState): String {
    if (state.isLoading) return " "
    val parts = mutableListOf<String>()
    if (state.stats.dueNow > 0) parts += "待复习 ${state.stats.dueNow}"
    if (state.newRemainingToday > 0) parts += "新词 ${state.newRemainingToday}"
    if (state.stats.reviewedToday > 0) parts += "今日已学 ${state.stats.reviewedToday}"
    if (parts.isEmpty()) {
        return if (state.stats.totalWords == 0) "还没有词书" else "今天都学完了"
    }
    return parts.joinToString(" · ")
}
