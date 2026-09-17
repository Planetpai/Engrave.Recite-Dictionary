package top.southfish.engrave.ui.mistakes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import top.southfish.engrave.data.local.entity.MistakeEntity
import top.southfish.engrave.ui.components.SwipeLeftRow
import top.southfish.engrave.ui.components.rememberRotaryScrollable
import top.southfish.engrave.ui.vocabViewModel

/**
 * 错题本：搜索页滑进来的和详情页手动加入的词都在这里。点词进详情。
 *
 * 防误触：普通模式左滑无效；点「选择删除」进入删除模式后，左滑把词标记
 * 为待删（变红），再滑取消；点「确定删除」后先预览要删的词，再次确认才删。
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
    val state by viewModel.uiState.collectAsState()
    val listState = rememberScalingLazyListState()
    rememberRotaryScrollable(listState)

    Scaffold(
        modifier = modifier,
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = top.southfish.engrave.ui.components.timeTextPadding(),
                bottom = 16.dp
            )
        ) {
            item { ListHeader { Text("错题集 ${state.mistakes.size}") } }

            item {
                androidx.wear.compose.material.Button(
                    onClick = onStartDrill,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                ) { Text("随刷随学") }
            }

            item {
                Button(
                    onClick = viewModel::toggleDeleteMode,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = when (state.deleteStage) {
                            DeleteStage.MARKING -> MaterialTheme.colors.error
                            else -> MaterialTheme.colors.surface
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text(
                        when (state.deleteStage) {
                            DeleteStage.NONE -> "选择删除"
                            DeleteStage.MARKING -> "取消选择"
                            DeleteStage.CONFIRM -> "返回"
                        }
                    )
                }
                if (state.deleteStage == DeleteStage.MARKING) {
                    Text(
                        text = "左滑标记要删除的单词，再滑取消",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                    )
                }
            }

            if (state.mistakes.isEmpty()) {
                item {
                    Text(
                        "还没有错题\n在搜索页左滑单词可以加进来",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            items(state.mistakes, key = { it.term }) { item ->
                val marked = item.term in state.marked
                if (state.deleteStage == DeleteStage.MARKING) {
                    // 删除模式：左滑标记 / 取消标记，标记的变红
                    SwipeLeftRow(
                        onSwipe = { viewModel.toggleMark(item.term) },
                        hint = if (marked) "取消标记" else "标记删除"
                    ) {
                        MistakeRow(item, marked = marked, onClick = { onOpenWord(item.term) })
                    }
                } else {
                    MistakeRow(item, marked = false, onClick = { onOpenWord(item.term) })
                }
            }

            if (state.deleteStage == DeleteStage.MARKING && state.marked.isNotEmpty()) {
                item {
                    Button(
                        onClick = viewModel::showConfirm,
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) { Text("确定删除 (${state.marked.size})") }
                }
            }
        }
    }

    // 第二次确认：预览要删除的词
    if (state.deleteStage == DeleteStage.CONFIRM) {
        ConfirmDeleteOverlay(
            terms = state.marked.toList(),
            onConfirm = viewModel::deleteMarked,
            onDismiss = viewModel::cancelConfirm
        )
    }
}

@Composable
private fun MistakeRow(item: MistakeEntity, marked: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (marked) Color(0xFF5A2028) else MaterialTheme.colors.surface)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = item.term + if (marked) " ✗" else "",
            style = MaterialTheme.typography.body1,
            color = if (marked) MaterialTheme.colors.error else MaterialTheme.colors.onSurface
        )
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

/** 删除确认层：列出待删的词 + 确认 / 取消。 */
@Composable
private fun ConfirmDeleteOverlay(
    terms: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "将删除以下 ${terms.size} 个词：\n" + terms.joinToString("、"),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            androidx.wear.compose.material.Button(
                onClick = onConfirm,
                colors = androidx.wear.compose.material.ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) { Text("确认删除") }
            androidx.wear.compose.material.Button(
                onClick = onDismiss,
                colors = androidx.wear.compose.material.ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) { Text("取消") }
        }
    }
}
