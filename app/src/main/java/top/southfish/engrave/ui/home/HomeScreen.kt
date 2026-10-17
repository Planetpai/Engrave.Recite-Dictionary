package top.southfish.engrave.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import top.southfish.engrave.ui.components.AnimatedTimeText
import top.southfish.engrave.ui.components.PageDots
import top.southfish.engrave.ui.components.formatProgress
import top.southfish.engrave.ui.components.rememberRotaryScrollable
import top.southfish.engrave.ui.components.rememberTimeTextVisible
import top.southfish.engrave.ui.profile.ProfileContent
import top.southfish.engrave.ui.vocabViewModel

/**
 * 首页 + 我的页，横滑切换（大纲：初始页从右往左滑为用户页）。
 *
 * 一个 Scaffold 挂 TimeText，两个页面各自是 ScalingLazyColumn；
 * 位置指示条和表圈滚动都只跟随当前页。侧滑返回在导航层保留——
 * 从左缘右滑仍然是系统返回，和横滑换页不冲突。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onStartStudy: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenBooks: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOtherSettings: () -> Unit,
    onOpenData: () -> Unit,
    onOpenMistakes: () -> Unit,
    onPickAvatar: () -> Unit,
    onOpenTutorial: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = vocabViewModel<HomeViewModel> { container ->
        HomeViewModel(
            studyRepository = container.studyRepository,
            settingsRepository = container.settingsRepository,
            wordRepository = container.wordRepository,
            sessionStore = container.sessionStore
        )
    }
    val state by viewModel.uiState.collectAsState()

    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    val homeListState = rememberScalingLazyListState()
    val profileListState = rememberScalingLazyListState()

    // 时间条跟随当前页的列表：列表在顶部或往回滑时显示，往下翻就隐藏
    val homeTimeVisible = rememberTimeTextVisible(homeListState)
    val profileTimeVisible = rememberTimeTextVisible(profileListState)

    // 表圈滚动全屏只挂这一处：motion listener 单槽位，内层页面再挂会把
    // 这里的覆盖掉（这正是首页表圈失灵的根因）。目标列表由重组自动更新。
    rememberRotaryScrollable(
        state = if (pagerState.currentPage == 0) homeListState else profileListState
    )

    Scaffold(
        modifier = modifier,
        timeText = {
            val currentVisible = if (pagerState.currentPage == 0) homeTimeVisible else profileTimeVisible
            AnimatedTimeText(currentVisible)
        },
        positionIndicator = {
            PositionIndicator(
                scalingLazyListState = if (pagerState.currentPage == 0) homeListState else profileListState
            )
        }
    ) {
        Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> HomeContent(
                    state = state,
                    onStartStudy = onStartStudy,
                    onOpenSearch = onOpenSearch,
                    onOpenMistakes = onOpenMistakes,
                    listState = homeListState
                )

                else -> ProfileContent(
                    onOpenStats = onOpenStats,
                    onOpenBooks = onOpenBooks,
                    onOpenSettings = onOpenSettings,
                    onOpenOtherSettings = onOpenOtherSettings,
                    onOpenData = onOpenData,
                    onPickAvatar = onPickAvatar,
                    onOpenTutorial = onOpenTutorial,
                    listState = profileListState
                )
            }
        }
            PageDots(
                pagerState = pagerState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            )
        }
    }
}

/** 初始页：学习进度、今日任务、开始学习，顶部一个胶囊搜索入口。 */
@Composable
private fun HomeContent(
    state: HomeUiState,
    onStartStudy: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenMistakes: () -> Unit,
    listState: ScalingLazyListState
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            // 方形屏 TimeText 占顶部正中，需要让到 28dp；圆形屏保持 8dp
            top = top.southfish.engrave.ui.components.timeTextPadding(8.dp),
            bottom = 32.dp
        )
    ) {
        item {
            // 顶部胶囊搜索入口（仿系统应用样式）：扁平放大镜 + 「搜索」
            // wear 的 Button content 是 BoxScope，横排要自己包 Row
            Button(
                onClick = onOpenSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp, vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.wear.compose.material.Icon(
                        painter = androidx.compose.ui.res.painterResource(
                            top.southfish.engrave.R.drawable.ic_search
                        ),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text("搜索")
                }
            }
        }

        item {
            Text(
                text = state.activeBookName ?: "全部词书",
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Text(
                text = if (state.isLoading) {
                    "准备词书…"
                } else {
                    formatProgress(state.stats.learnedWords, state.stats.totalWords)
                },
                style = MaterialTheme.typography.title1,
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
            // 按钮常开：没额度时也能进学习页（恢复断点或用「继续再学」加量）
            Button(
                onClick = onStartStudy,
                enabled = state.buttonEnabled,
                modifier = Modifier
                    .width(150.dp)
                    .padding(top = 8.dp)
            ) {
                Text(state.buttonLabel)
            }
        }

        item {
            // 错题本入口就在背单词下面
            Button(
                onClick = onOpenMistakes,
                colors = androidx.wear.compose.material.ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.surface
                ),
                modifier = Modifier
                    .width(150.dp)
                    .padding(top = 2.dp)
            ) {
                Text("错题本")
            }
        }
    }
}

/** 首页那行小字：今天有任务时列出来，做完了换成表扬。 */
private fun summaryLine(state: HomeUiState): String {
    if (state.isLoading) return " "
    if (state.stats.totalWords == 0) return "还没有词书"
    if (!state.hasAnythingToStudy) {
        return if (state.resumable) "有没背完的，点继续" else "今天计划完成，想加量点继续学习"
    }
    val parts = mutableListOf<String>()
    if (state.stats.dueNow > 0) parts += "待复习 ${state.stats.dueNow}"
    if (state.newRemainingToday > 0) parts += "新词 ${state.newRemainingToday}"
    if (state.stats.reviewedToday > 0) parts += "今日已学 ${state.stats.reviewedToday}"
    return parts.joinToString(" · ")
}
