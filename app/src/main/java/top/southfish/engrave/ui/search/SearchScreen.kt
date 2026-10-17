package top.southfish.engrave.ui.search

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import top.southfish.engrave.ui.components.AnimatedTimeText
import top.southfish.engrave.ui.components.PageDots
import top.southfish.engrave.data.local.entity.SearchHistoryEntity
import top.southfish.engrave.ui.components.SwipeLeftRow
import top.southfish.engrave.ui.components.rememberDynamicRotaryScroll
import top.southfish.engrave.ui.components.rememberTimeTextVisible
import top.southfish.engrave.ui.vocabViewModel

private val SearchFieldBackground = Color(0xFF1B202A)

/**
 * 搜索页：上面搜索框，下面历史 / 结果。
 *
 * 模糊搜索（ECDICT 子集）按 相似档位 + 词频 排序；空查询时展示历史，
 * 历史从右往左滑 = 加入错题集（大纲），同时把这条历史清掉。
 */
@OptIn(ExperimentalFoundationApi::class)
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
    // 三个列表的滚动状态都提在页面级：表圈监听器全页只挂一个（挂在子列表里会在
    // 输入首字母、历史→结果切换时重新 requestFocus，抢走输入框焦点导致误跳详情），
    // 按当前区域把表圈事件路由给对应列表。
    // 初值取 VM 里存的现场：跳详情时本页组合被销毁，返回时靠这些值恢复滚动位置。
    val historyPos = viewModel.historyPos()
    val wordPos = viewModel.wordPos()
    val phrasePos = viewModel.phrasePos()
    val historyListState = rememberScalingLazyListState(
        initialCenterItemIndex = historyPos.index,
        initialCenterItemScrollOffset = historyPos.offset
    )
    val wordListState = rememberScalingLazyListState(
        initialCenterItemIndex = wordPos.index,
        initialCenterItemScrollOffset = wordPos.offset
    )
    val phraseListState = rememberScalingLazyListState(
        initialCenterItemIndex = phrasePos.index,
        initialCenterItemScrollOffset = phrasePos.offset
    )
    val pagerState = rememberPagerState(
        initialPage = viewModel.pagerPage(),
        pageCount = { 2 }
    )
    // 时间条跟随当前区域的列表：在顶部或往回滑时显示，往下翻就隐藏
    val historyTimeVisible = rememberTimeTextVisible(historyListState)
    val wordTimeVisible = rememberTimeTextVisible(wordListState)
    val phraseTimeVisible = rememberTimeTextVisible(phraseListState)
    rememberDynamicRotaryScroll {
        if (state.query.isBlank()) {
            historyListState
        } else if (pagerState.currentPage == 0) {
            wordListState
        } else {
            phraseListState
        }
    }
    // 离开搜索页时确保键盘收起（自动弹出用了 SHOW_FORCED），并把当前
    // 滚动位置/分页存进 VM —— 跳详情再返回时本页组合重建，靠它恢复现场。
    val rootView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            val imm = rootView.context.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(rootView.windowToken, 0)
            if (viewModel.isSearching()) {
                viewModel.savePagerPage(pagerState.currentPage)
                viewModel.saveWordPos(
                    ListScrollPos(wordListState.centerItemIndex, wordListState.centerItemScrollOffset)
                )
                viewModel.savePhrasePos(
                    ListScrollPos(phraseListState.centerItemIndex, phraseListState.centerItemScrollOffset)
                )
            } else {
                viewModel.saveHistoryPos(
                    ListScrollPos(historyListState.centerItemIndex, historyListState.centerItemScrollOffset)
                )
            }
        }
    }

    Scaffold(
        modifier = modifier,
        timeText = {
            val timeVisible = if (state.query.isBlank()) historyTimeVisible
            else if (pagerState.currentPage == 0) wordTimeVisible
            else phraseTimeVisible
            AnimatedTimeText(timeVisible)
        },
        positionIndicator = {
            val activeListState = if (state.query.isBlank()) {
                historyListState
            } else if (pagerState.currentPage == 0) {
                wordListState
            } else {
                phraseListState
            }
            PositionIndicator(scalingLazyListState = activeListState)
        }
    ) {
        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 24.dp, start = 12.dp, end = 12.dp)
        ) {
            // 搜索框用原生 EditText（见 SearchField 内部说明）
            SearchField(onValueChange = viewModel::onQueryChange)

            Box(Modifier.weight(1f)) {
                if (state.query.isBlank()) {
                    HistoryList(
                        history = state.history,
                        onOpen = { viewModel.openWord(it.term, onOpenWord) },
                        onAddMistake = viewModel::addMistakeFromHistory,
                        onClear = viewModel::clearHistory,
                        listState = historyListState
                    )
                } else {
                    // 单词 / 词组 两栏，可左右滑动切换
                    ResultTabs(
                        pagerState = pagerState,
                        wordListState = wordListState,
                        phraseListState = phraseListState,
                        wordResults = state.results,
                        phrases = state.phrases,
                        onOpenWord = { viewModel.openWord(it.clickTarget, onOpenWord) },
                        onOpenPhrase = { viewModel.openWord(it.phrase, onOpenWord) }
                    )
                }
            }
        }
            if (!state.query.isBlank()) {
                PageDots(
                    pagerState = pagerState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                )
            }
        }
    }
}

/**
 * 搜索输入框。
 *
 * 特意用原生 [EditText] 而不是 Compose 的 BasicTextField：手表上
 * HoneyBoard 走全屏提取（extracted）编辑模式，Compose 1.5.x 的
 * BasicTextField 在「键盘收起后重新点开、字段里已有文字」的会话里，
 * 输入法的提取文本不再随输入刷新——用户打字时键盘上的文本条静止不动，
 * 看起来就是“打不出字”。原生 EditText 是 IME 提取机制的参考实现，
 * 全屏编辑条会实时同步，不存在这个问题。
 */
@Composable
private fun SearchField(onValueChange: (String) -> Unit) {
    val textColor = MaterialTheme.colors.onSurface.toArgb()
    val hintColor = MaterialTheme.colors.onSurfaceVariant.toArgb()
    val horizontalPadding = with(LocalDensity.current) { 14.dp.roundToPx() }

    var editText by remember { mutableStateOf<EditText?>(null) }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(SearchFieldBackground)
            // 点条上文字以外的区域也能聚焦并拉起键盘
            .clickable {
                editText?.let { field ->
                    field.requestFocus()
                    val imm = field.context.getSystemService(InputMethodManager::class.java)
                    imm?.showSoftInput(field, 0)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                EditText(ctx).apply {
                    background = null
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    setTextColor(textColor)
                    setHintTextColor(hintColor)
                    hint = "输入单词或中文"
                    textSize = 15f
                    setPadding(horizontalPadding, 0, horizontalPadding, 0)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            onValueChange(s?.toString().orEmpty())
                        }
                    })
                    setOnEditorActionListener { v, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            val imm = v.context.getSystemService(InputMethodManager::class.java)
                            imm?.hideSoftInputFromWindow(v.windowToken, 0)
                            v.clearFocus()
                            true
                        } else {
                            false
                        }
                    }
                    editText = this
                    // 进页面自动聚焦并拉起键盘（等转场动画过去）
                    postDelayed(
                        {
                            requestFocus()
                            val imm = context.getSystemService(InputMethodManager::class.java)
                            imm?.showSoftInput(this, InputMethodManager.SHOW_FORCED)
                        },
                        400
                    )
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
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = top.southfish.engrave.ui.components.timeTextPadding(),
            bottom = 16.dp
        )
    ) {
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

/**
 * 搜索结果双栏：左「单词」右「词组」，左右滑切换（无标签栏省空间）。
 * 两栏各自持有滚动状态，切栏后回来位置保留。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultTabs(
    pagerState: androidx.compose.foundation.pager.PagerState,
    wordListState: ScalingLazyListState,
    phraseListState: ScalingLazyListState,
    wordResults: List<SearchWordResult>,
    phrases: List<top.southfish.engrave.domain.model.PhraseEntry>,
    onOpenWord: (SearchWordResult) -> Unit,
    onOpenPhrase: (top.southfish.engrave.domain.model.PhraseEntry) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            if (page == 0) {
                WordResultList(wordResults, onOpenWord, wordListState)
            } else {
                PhraseResultList(phrases, onOpenPhrase, phraseListState)
            }
        }
    }
}

@Composable
private fun WordResultList(
    results: List<SearchWordResult>,
    onOpen: (SearchWordResult) -> Unit,
    listState: ScalingLazyListState
) {
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)
    ) {
        if (results.isEmpty()) {
            item {
                Text(
                    "没有相关单词",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                )
            }
        }
        items(
            results,
            key = { (if (it.inflectionHint == null) "w_" else "i_") + it.entry.term + "_" + it.clickTarget }
        ) { result ->
            val entry = result.entry
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colors.surface)
                    .clickable { onOpen(result) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.term, style = MaterialTheme.typography.body1)
                    // 变形标注条不显示音标
                    if (result.inflectionHint == null) {
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
                }
                // 变形标注条第二行是「die 的现在分词」式标注，正常条是释义首行
                val secondLine = result.inflectionHint
                    ?: entry.meaningZh?.let { it.lineSequence().firstOrNull() ?: it }
                secondLine?.let {
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

@Composable
private fun PhraseResultList(
    phrases: List<top.southfish.engrave.domain.model.PhraseEntry>,
    onOpen: (top.southfish.engrave.domain.model.PhraseEntry) -> Unit,
    listState: ScalingLazyListState
) {
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)
    ) {
        if (phrases.isEmpty()) {
            item {
                Text(
                    "没有相关短语",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                )
            }
        }
        items(phrases, key = { "p_" + it.phrase }) { p ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colors.surface)
                    .clickable { onOpen(p) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(p.phrase, style = MaterialTheme.typography.body1)
                if (p.zh.isNotBlank()) {
                    Text(
                        p.zh,
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
