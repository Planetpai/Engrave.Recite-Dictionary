package top.southfish.engrave.ui.stats

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import top.southfish.engrave.ui.components.Chip
import top.southfish.engrave.ui.components.ChipDefaults
import top.southfish.engrave.ui.components.ListHeader
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text

import top.southfish.engrave.ui.components.formatRate
import top.southfish.engrave.ui.components.rememberRotaryScrollable
import top.southfish.engrave.ui.vocabViewModel

@Composable
fun StatsScreen(modifier: Modifier = Modifier) {
    val viewModel = vocabViewModel<StatsViewModel> { container ->
        StatsViewModel(
            studyRepository = container.studyRepository,
            settingsRepository = container.settingsRepository
        )
    }
    val state by viewModel.uiState.collectAsState()

    val listState = rememberLazyListState()
    rememberRotaryScrollable(listState)

    Scaffold(
        modifier = modifier,
                    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = top.southfish.engrave.ui.components.timeTextPadding(),
                bottom = 16.dp
            )
        ) {
            item {
                ListHeader { Text("学习统计") }
            }

            val stats = state.stats
            statRow("总词数", "${stats.totalWords}")
            statRow("已学", "${stats.learnedWords}")
            statRow("待复习", "${stats.dueNow}")
            statRow("新词剩余", "${stats.newRemaining}")
            statRow("今日复习", "${stats.reviewedToday}")
            statRow("今日正确率", formatRate(stats.correctTodayRate))
            statRow("连续天数", if (stats.streakDays > 0) "${stats.streakDays} 天" else "—")
            statRow("每日新词上限", "${state.dailyNewLimit}")
        }
    }
}

/**
 * 一行「标签 + 数值」。用 LazyListScope 的扩展函数写，
 * 这样调用处能像声明式列表一样一行一条，不必每条都包一层 item {}。
 */
private fun LazyListScope.statRow(
    label: String,
    value: String
) {
    item {
        Text(
            text = "$label  $value",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
