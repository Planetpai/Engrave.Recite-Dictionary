package com.example.vocabwatch.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.vocabwatch.data.local.entity.SearchHistoryEntity
import com.example.vocabwatch.ui.components.SwipeLeftRow
import com.example.vocabwatch.ui.components.rememberRotaryScrollable
import com.example.vocabwatch.ui.vocabViewModel

private val SearchFieldBackground = Color(0xFF1B202A)

/**
 * 搜索页：上面搜索框，下面历史 / 结果。
 *
 * 模糊搜索（ECDICT 子集）按 相似档位 + 词频 排序；空查询时展示历史，
 * 历史从右往左滑 = 加入错题集（大纲），同时把这条历史清掉。
 */
@Composable
fun SearchScreen(
    onOpenWord: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = vocabViewModel<SearchViewModel> { container ->
        SearchViewModel(
            dictRepository = container.dictRepository,
            searchDao = container.searchDao,
            mistakeDao = container.mistakeDao
        )
    }
    val state by viewModel.uiState.collectAsState()
    val listState = rememberScalingLazyListState()
    rememberRotaryScrollable(listState)
    // 手表上要显式抢焦点，系统输入法才会弹出来：进页面自动聚焦搜索框
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(400)
        runCatching { focusRequester.requestFocus() }
    }
    // 输入框的值必须用本地即时状态：绑到防抖后的 uiState.query 会让
    // 快速输入的字符被旧值回写吃掉（手表上表现为“键盘打不了字”）
    var searchText by remember { mutableStateOf("") }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    Scaffold(
        modifier = modifier,
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 24.dp, start = 12.dp, end = 12.dp)
        ) {
            SearchField(
                value = searchText,
                onValueChange = {
                    searchText = it
                    viewModel.onQueryChange(it)
                },
                // 确认键只收键盘：停留在结果列表里自己挑，不自动跳详情
                onDone = { focusManager.clearFocus() },
                focusRequester = focusRequester
            )

            Box(Modifier.weight(1f)) {
                if (state.query.isBlank()) {
                    HistoryList(
                        history = state.history,
                        onOpen = { viewModel.openWord(it.term, onOpenWord) },
                        onAddMistake = viewModel::addMistakeFromHistory,
                        onClear = viewModel::clearHistory,
                        listState = listState
                    )
                } else {
                    ResultList(
                        results = state.results,
                        searching = state.searching,
                        onOpen = { viewModel.openWord(it.term, onOpenWord) },
                        listState = listState
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    focusRequester: FocusRequester
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(SearchFieldBackground)
            .clickable { focusRequester.requestFocus() },
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = MaterialTheme.colors.onSurface,
                fontSize = 15.sp
            ),
            cursorBrush = SolidColor(MaterialTheme.colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .focusRequester(focusRequester),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (value.isEmpty()) {
                        Text(
                            "输入单词或中文",
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onSurfaceVariant
                        )
                    }
                    inner()
                }
            }
        )
    }
}

@Composable
private fun HistoryList(
    history: List<SearchHistoryEntity>,
    onOpen: (SearchHistoryEntity) -> Unit,
    onAddMistake: (String) -> Unit,
    onClear: () -> Unit,
    listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState
) {
    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            ListHeader { Text("搜索历史") }
        }
        if (history.isEmpty()) {
            item {
                Text(
                    "还没有查过词",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        items(history, key = { it.term }) { item ->
            // 左滑 = 加入错题集（顺手把这条历史清掉）
            SwipeLeftRow(
                onSwipe = { onAddMistake(item.term) },
                hint = "＋ 错题集"
            ) {
                HistoryRow(item, onOpen = { onOpen(item) })
            }
        }
        if (history.isNotEmpty()) {
            item {
                Chip(
                    label = { Text("清空历史") },
                    onClick = onClear,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(item: SearchHistoryEntity, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colors.surface)
            .clickable(onClick = onOpen)
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

@Composable
private fun ResultList(
    results: List<com.example.vocabwatch.domain.model.DictEntry>,
    searching: Boolean,
    onOpen: (com.example.vocabwatch.domain.model.DictEntry) -> Unit,
    listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState
) {
    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        if (results.isEmpty()) {
            item {
                Text(
                    if (searching) "搜索中…" else "没有找到",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                )
            }
        }
        items(results, key = { it.term }) { entry ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colors.surface)
                    .clickable { onOpen(entry) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.term, style = MaterialTheme.typography.body1)
                    entry.phonetic?.let {
                        Text(
                            "  $it",
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                entry.meaningZh?.let {
                    Text(
                        it.lineSequence().firstOrNull() ?: it,
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
