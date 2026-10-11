package top.southfish.engrave.ui.search

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import top.southfish.engrave.data.dictionary.DictRepository
import top.southfish.engrave.domain.model.DefinitionDisplay
import top.southfish.engrave.ui.components.rememberRotaryScrollable
import top.southfish.engrave.ui.vocabViewModel

/**
 * 词详情：单词 + 音标 → 错题集开关 → 释义（按用户页的偏好显示中/英/中英）
 * → 词形变换（过去式/过去分词等）→ 例句（词书里有的话）。
 * 数据来自 ECDICT 离线词典，例句来自内置词书。
 */
@Composable
fun WordDetailScreen(
    term: String,
    onOpenWord: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val viewModel = vocabViewModel<WordDetailViewModel> { container ->
        WordDetailViewModel(
            term = term,
            dictRepository = container.dictRepository,
            mistakeDao = container.mistakeDao,
            wordRepository = container.wordRepository,
            settingsRepository = container.settingsRepository,
            ttsSpeaker = container.ttsSpeaker
        )
    }
    val state by viewModel.uiState.collectAsState()
    // 初值取 VM 里存的现场：点派生词跳详情时本页组合被销毁，返回时靠它恢复滚动位置。
    val savedPos = viewModel.scrollPos()
    val listState = rememberScalingLazyListState(
        initialCenterItemIndex = savedPos.index,
        initialCenterItemScrollOffset = savedPos.offset
    )
    rememberRotaryScrollable(listState)
    // 离开本页时把滚动位置存进 VM（VM 挂在导航条目上，返回时仍存活）
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            // 空态帧（数据重载瞬间）列表 item 骤减、center 会被钳到顶部，
            // 此刻离场不能把污染后的位置写回 VM，保留跳转前存下的现场。
            val latest = viewModel.uiState.value
            if (latest.entry != null || latest.phrase != null) {
                viewModel.saveScrollPos(
                    ListScrollPos(listState.centerItemIndex, listState.centerItemScrollOffset)
                )
            }
        }
    }

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
            item {
                // 点击单词标题发音（设置里关掉发音开关后不可点）
                Text(
                    text = term,
                    style = MaterialTheme.typography.title1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { viewModel.speakTerm() }
                )
            }

            state.entry?.phonetic?.let { phonetic ->
                item {
                    Text(
                        text = phonetic,
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Chip(
                    label = { Text(if (state.inMistakes) "已在错题集 · 点移出" else "加入错题集") },
                    onClick = { viewModel.toggleMistake(term) },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = if (state.inMistakes) {
                            MaterialTheme.colors.primary
                        } else {
                            MaterialTheme.colors.surface
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                )
            }

            val entry = state.entry
            val phrase = state.phrase
            when {
                entry == null && phrase == null -> {
                    item {
                        Text(
                            "词典加载中…",
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                        )
                    }
                }
                // 短语详情：只有标题（上方）+ 中文释义，其余栏全部隐藏
                entry == null && phrase != null -> {
                    definitionSection("释义", phrase.zh.split("\n"))
                }
                entry != null -> {
                // entry 来自 :core 模块，属性跨模块不能 smart cast，先落局部变量
                val meaningZh = entry.meaningZh
                val definitionEn = entry.definitionEn
                if (state.display != DefinitionDisplay.EN && !meaningZh.isNullOrBlank()) {
                    definitionSection("释义", meaningZh.split("\n"))
                }
                if (state.display != DefinitionDisplay.ZH && !definitionEn.isNullOrBlank()) {
                    definitionSection("English", definitionEn.split("\n"))
                }

                // 词形变化：原型行 + 自身关系说明 + 各变形列表。纯文本展示，不做跳转。
                val exchangeInfo = state.exchangeInfo
                if (exchangeInfo != null) {
                    val lemma = exchangeInfo.lemma
                    // 变形词：显示「原型 xxx」（原型与自身不同才显示）
                    if (lemma != null && !lemma.equals(term, ignoreCase = true)) {
                        item { ListHeader { Text("词形变化") } }
                        item {
                            FormTextRow(label = "原型", word = lemma)
                        }
                        // 该词自身相对原型的关系（如 dying → die 的现在分词）
                        exchangeInfo.selfRelation?.let { rel ->
                            val relText = rel.map { DictRepository.EXCHANGE_LABELS[it.toString()] ?: it.toString() }
                                .distinct().joinToString("、")
                            item {
                                Text(
                                    text = "本词是 $lemma 的 $relText",
                                    style = MaterialTheme.typography.caption2,
                                    color = MaterialTheme.colors.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    // 原型词：列出自身各变形
                    val forms = exchangeInfo.forms
                    if (forms.isNotEmpty() && (lemma == null || lemma.equals(term, ignoreCase = true))) {
                        if (lemma == null) item { ListHeader { Text("词形变化") } }
                        forms.forEach { (code, word) ->
                            item {
                                FormTextRow(
                                    label = DictRepository.EXCHANGE_LABELS[code] ?: code,
                                    word = word
                                )
                            }
                        }
                    }
                }

                if (state.derivatives.isNotEmpty()) {
                    item { ListHeader { Text("派生") } }
                    state.derivatives.forEach { derived ->
                        item {
                            Text(
                                text = derivedLine(derived),
                                style = MaterialTheme.typography.body2,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onOpenWord(derived.term) }
                                    .padding(horizontal = 16.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                state.bookWord?.example?.let { example ->
                    item { ListHeader { Text("例句") } }
                    item {
                        Text(
                            text = example,
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 1.dp)
                        )
                    }
                    state.bookWord?.exampleTranslation?.let { translation ->
                        item {
                            Text(
                                text = translation,
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                // 关联短语：详情页最底部。点击条目跳转到该短语的「详情」（以短语为词查）。
                if (state.phrases.isNotEmpty()) {
                    item { ListHeader { Text("关联短语") } }
                    state.phrases.forEach { p ->
                        item {
                            PhraseRow(
                                phrase = p.phrase,
                                zh = p.zh,
                                onClick = { onOpenWord(p.phrase) }
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

/** 词形变化的纯文本行：「过去式  played」，仅展示不跳转。 */
@Composable
private fun FormTextRow(label: String, word: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp)
    ) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant
            )
        }
        Text(
            text = word,
            style = MaterialTheme.typography.body2,
            modifier = Modifier.padding(start = if (label.isBlank()) 0.dp else 8.dp)
        )
    }
}

/** 关联短语行：短语 + 中文释义。点击跳转该短语的详情。 */
@Composable
private fun PhraseRow(phrase: String, zh: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(phrase, style = MaterialTheme.typography.body2)
        if (zh.isNotBlank()) {
            Text(
                zh,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant
            )
        }
    }
}

/** 派生行：differently (adv.) 不同地。词性从释义开头推断（ECDICT 惯例）。 */
private fun derivedLine(entry: top.southfish.engrave.domain.model.DictEntry): String {
    val firstLine = entry.meaningZh?.lineSequence()?.firstOrNull()?.trim()
    val pos = firstLine?.let { Regex("^([a-z]+\\.)\\s*").find(it)?.groupValues?.get(1) }
    val meaning = firstLine?.replaceFirst(Regex("^[a-z]+\\.\\s*"), "") ?: ""
    val tag = pos?.let { " [$it]" } ?: ""
    return entry.term + tag + if (meaning.isBlank()) "" else "  $meaning"
}

/** 释义块：小标题 + 逐行正文，每行一个列表项——
 *  ScalingLazyColumn 以屏幕中心为锚点定位 item，单独成行才能保证
 *  最长的那条释义也能滚到圆屏中央完整显示，不被圆屏下缘切掉。 */
private fun ScalingLazyListScope.definitionSection(title: String, lines: List<String>) {
    item { ListHeader { Text(title) } }
    lines.filter { it.isNotBlank() }.forEach { line ->
        item {
            Text(
                text = line.trim(),
                style = MaterialTheme.typography.body2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 1.dp)
            )
        }
    }
}
