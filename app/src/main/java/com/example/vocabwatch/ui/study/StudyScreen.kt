package com.example.vocabwatch.ui.study

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.vocabwatch.data.session.StudySessionStore
import com.example.vocabwatch.ui.vocabViewModel
import java.io.File

/**
 * 学习页。
 *
 * 整页不滚动，内容就是一叠卡片加手势，所以没有 ScalingLazyColumn，也没有
 * 位置指示条。侧滑返回在导航层被关掉了，只能按返回键退出——队列快照会在
 * 每次状态变化时持久化，退出再进来从断点继续。
 */
@Composable
fun StudyScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = vocabViewModel<StudyViewModel> { container ->
        StudyViewModel(
            getStudySession = container.getStudySession,
            submitReview = container.submitReview,
            undoReview = container.undoReview,
            settingsRepository = container.settingsRepository,
            timeProvider = container.timeProvider,
            sessionStore = StudySessionStore(container.appContext.filesDir),
            restoreSession = container.restoreSession,
            getAgainSession = container.getAgainSession,
            mistakeDao = container.mistakeDao
        )
    }
    val state by viewModel.uiState.collectAsState()

    Scaffold(modifier = modifier, timeText = { TimeText() }) {
        Box(Modifier.fillMaxSize()) {
            when (val current = state) {
                StudyUiState.Loading -> CenteredMessage("准备卡片…")

                StudyUiState.Empty -> EndScreen(
                    title = "今天都学完了",
                    subtitle = "没有新词，也没有到期的复习",
                    viewModel = viewModel,
                    onExit = onExit
                )

                is StudyUiState.Break -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
                ) {
                    CenteredMessage(current.title)
                    CenteredMessage(current.subtitle, MaterialTheme.colors.onSurfaceVariant)
                    Button(
                        onClick = { viewModel.onBreakAction(current.onPrimary) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(current.primaryLabel)
                    }
                    current.secondaryLabel?.let { label ->
                        Button(
                            onClick = { viewModel.onBreakAction(current.onSecondary!!) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(label) }
                    }
                }

                is StudyUiState.Browsing -> BrowsingPhase(current, viewModel)

                is StudyUiState.Testing -> TestingPhase(current, viewModel)

                is StudyUiState.Finished -> EndScreen(
                    title = "本轮完成",
                    subtitle = "新词 ${current.newCount} · 复习 ${current.reviewCount}" +
                        if (current.againCount > 0) " · 答错 ${current.againCount} 次" else "",
                    viewModel = viewModel,
                    onExit = onExit
                )
            }
        }
    }
}

/**
 * 学完后的收尾页（本轮完成 / 今日全空共用）：「继续再学」按用户选的数量
 * 忽略每日上限再学一批，「休息一下」退出学习页。
 */
@Composable
private fun EndScreen(
    title: String,
    subtitle: String,
    viewModel: StudyViewModel,
    onExit: () -> Unit
) {
    var pickingCount by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        CenteredMessage(title)
        CenteredMessage(subtitle, MaterialTheme.colors.onSurfaceVariant)

        if (pickingCount) {
            Text(
                "再学几个？",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(5, 10, 15, 20).forEach { count ->
                    Button(
                        onClick = { viewModel.continueLearning(count) },
                        modifier = Modifier.weight(1f)
                    ) { Text("$count") }
                }
            }
        } else {
            Button(
                onClick = { pickingCount = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("继续再学") }
        }

        Button(
            onClick = onExit,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = MaterialTheme.colors.surface
            ),
            modifier = Modifier.fillMaxWidth()
        ) { Text("休息一下") }
    }
}

/** 顶部一行进度/阶段说明。停驻露例句时整行收起，把中段让给卡片和例句。 */
@Composable
internal fun StudyHeader(text: String, collapsed: Boolean = false) {
    val headerHeight by animateDpAsState(if (collapsed) 0.dp else 18.dp, label = "headerHeight")
    val headerAlpha by animateFloatAsState(if (collapsed) 0f else 1f, label = "headerAlpha")
    Box(Modifier.fillMaxWidth().height(headerHeight)) {
        Text(
            text = text,
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .graphicsLayer { alpha = headerAlpha }
        )
    }
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
