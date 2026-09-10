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
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
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
 * 一行「标签 + 数值」。用 ScalingLazyListScope 的扩展函数写，
 * 这样调用处能像声明式列表一样一行一条，不必每条都包一层 item {}。
 */
private fun ScalingLazyListScope.statRow(
    label: String,
    value: String
) {
    item {
        Text(
            text = "$label  $value",
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
