package com.example.vocabwatch.ui.study

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
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.vocabwatch.ui.vocabViewModel

/**
 * 学习页。
 *
 * 整页不滚动，内容就是一叠卡片加手势，所以没有 ScalingLazyColumn，也没有
 * 位置指示条。侧滑返回在导航层被关掉了，只能按返回键退出——每次评分都
 * 已经落库，退出即保存。
 */
@Composable
fun StudyScreen(modifier: Modifier = Modifier) {
    val viewModel = vocabViewModel<StudyViewModel> { container ->
        StudyViewModel(
            getStudySession = container.getStudySession,
            submitReview = container.submitReview,
            undoReview = container.undoReview,
            settingsRepository = container.settingsRepository,
            timeProvider = container.timeProvider
        )
    }
    val state by viewModel.uiState.collectAsState()

    Scaffold(modifier = modifier, timeText = { TimeText() }) {
        Box(Modifier.fillMaxSize()) {
            when (val current = state) {
                StudyUiState.Loading -> CenteredMessage("准备卡片…")

                StudyUiState.Empty -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
                ) {
                    CenteredMessage("今天学完了")
                    CenteredMessage("没有新词，也没有到期的复习", MaterialTheme.colors.onSurfaceVariant)
                    Chip(label = { Text("重新加载") }, onClick = viewModel::startSession, modifier = Modifier.fillMaxWidth())
                }

                is StudyUiState.Break -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
                ) {
                    CenteredMessage(current.title)
                    CenteredMessage(current.subtitle, MaterialTheme.colors.onSurfaceVariant)
                    Button(onClick = viewModel::continueAfterBreak, modifier = Modifier.fillMaxWidth()) {
                        Text(current.actionLabel)
                    }
                }

                is StudyUiState.Browsing -> BrowsingPhase(current, viewModel)

                is StudyUiState.Testing -> TestingPhase(current, viewModel)

                is StudyUiState.Finished -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
                ) {
                    CenteredMessage("本轮完成")
                    CenteredMessage(
                        "新词 ${current.newCount} · 复习 ${current.reviewCount}" +
                            if (current.againCount > 0) " · 答错 ${current.againCount} 次" else "",
                        MaterialTheme.colors.onSurfaceVariant
                    )
                    Chip(label = { Text("再来一组") }, onClick = viewModel::startSession, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** 顶部一行进度/阶段说明。 */
@Composable
internal fun StudyHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption2,
        color = MaterialTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    )
}

@Composable
internal fun CenteredMessage(
    text: String,
    color: Color = MaterialTheme.colors.onSurface
) {
    Text(
        text = text,
        style = MaterialTheme.typography.body1,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}
