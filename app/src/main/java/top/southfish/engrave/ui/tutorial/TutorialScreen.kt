package top.southfish.engrave.ui.tutorial

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import top.southfish.engrave.ui.components.rememberRotaryScrollable
import top.southfish.engrave.ui.components.timeTextPadding

/**
 * 使用指引：把卡片手势一屏列完，从「我的」页进入。
 *
 * 纯静态说明页，不连 ViewModel。交互改了要同步改这里的文案——
 * 卡片上不再贴「←/↑」手势徽章，新用户唯一的说明入口就是这页。
 */
@Composable
fun TutorialScreen(modifier: Modifier = Modifier) {
    val listState = rememberScalingLazyListState()
    rememberRotaryScrollable(listState)

    Scaffold(
        modifier = modifier,
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                top = timeTextPadding(8.dp),
                bottom = 32.dp
            )
        ) {
            item { ListHeader { Text("使用指引") } }

            item {
                GuideSection(
                    title = "浏览选词",
                    lines = listOf(
                        "点一下卡片：翻面预览释义",
                        "左滑 = 已学过，直接排期",
                        "上滑 = 不认识，加入待背"
                    )
                )
            }
            item {
                GuideSection(
                    title = "背单词 / 复习",
                    lines = listOf(
                        "点一下卡片：翻面看答案",
                        "翻面后左滑 = 会，上滑 = 不会",
                        "不会的词沉底重来",
                        "每个词过三遍：英→中→拼写"
                    )
                )
            }
            item {
                GuideSection(
                    title = "拼写卡",
                    lines = listOf(
                        "翻面后点乱序字母，按顺序拼出单词"
                    )
                )
            }
            item {
                GuideSection(
                    title = "通用",
                    lines = listOf(
                        "长按卡片：加入错题集",
                        "双击卡片外空白：撤销上一张"
                    )
                )
            }
        }
    }
}

/** 一节说明：彩色小标题 + 若干条手势说明。 */
@Composable
private fun GuideSection(title: String, lines: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.title3,
            color = MaterialTheme.colors.primary
        )
        lines.forEach {
            Text(
                text = it,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
