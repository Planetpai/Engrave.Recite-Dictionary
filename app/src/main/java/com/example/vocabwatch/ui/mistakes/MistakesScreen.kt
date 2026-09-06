package com.example.vocabwatch.ui.mistakes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.vocabwatch.ui.components.SwipeLeftRow
import com.example.vocabwatch.ui.components.rememberRotaryScrollable
import com.example.vocabwatch.ui.vocabViewModel

/**
 * 错题集：搜索页滑进来的和详情页手动加入的词都在这里。
 * 点词进详情，左滑移出错题集。
 */
@Composable
fun MistakesScreen(
    onOpenWord: (String) -> Unit,
    onStartDrill: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = vocabViewModel<MistakesViewModel> { container ->
        MistakesViewModel(mistakeDao = container.mistakeDao)
    }
    val mistakes by viewModel.mistakes.collectAsState()
    val listState = rememberScalingLazyListState()
    rememberRotaryScrollable(listState)

    Scaffold(
        modifier = modifier,
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("错题集 ${mistakes.size}") } }

            item {
                // 随刷随学：只过一轮 英文→中文，不会的继续循环
                androidx.wear.compose.material.Button(
                    onClick = onStartDrill,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                ) { Text("随刷随学") }
            }

            if (mistakes.isEmpty()) {
                item {
                    Text(
                        "还没有错题\n在搜索结果里左滑单词可以加进来",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            items(mistakes, key = { it.term }) { item ->
                SwipeLeftRow(
                    onSwipe = { viewModel.remove(item.term) },
                    hint = "移出错题集"
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colors.surface)
                            .clickable { onOpenWord(item.term) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(item.term, style = MaterialTheme.typography.body1)
                        item.meaning?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
