@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package top.southfish.engrave.ui.search

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import top.southfish.engrave.ui.components.Chip
import top.southfish.engrave.ui.components.ChipDefaults
import top.southfish.engrave.ui.components.ListHeader
import top.southfish.engrave.data.dictionary.DictRepository
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text

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
            settingsRepository = container.settingsRepository
        )
    }
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    rememberRotaryScrollable(listState)

    Scaffold(
        modifier = modifier,
                    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = top.southfish.engrave.ui.components.timeTextPadding(),
                bottom = 16.dp
            )
        ) {
            item {
                Text(
                    text = term,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            state.entry?.phonetic?.let { phonetic ->
                item {
                    Text(
                        text = phonetic,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                )
            }

            val entry = state.entry
            if (entry == null) {
                item {
                    Text(
                        "词典加载中…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    )
                }
            } else {
                // entry 来自 :core 模块，属性跨模块不能 smart cast，先落局部变量
                val meaningZh = entry.meaningZh
                val definitionEn = entry.definitionEn
                if (state.display != DefinitionDisplay.EN && !meaningZh.isNullOrBlank()) {
                    definitionSection("释义", meaningZh.split("\n"))
                }
                if (state.display != DefinitionDisplay.ZH && !definitionEn.isNullOrBlank()) {
                    definitionSection("English", definitionEn.split("\n"))
                }

                // 词形变化：原型跳转（变形词） + 变形列表（原型词）。统一走 exchangeInfo。
                val exchangeInfo = state.exchangeInfo
                if (exchangeInfo != null) {
                    val lemma = exchangeInfo.lemma
                    // 变形词：显示「原型 xxx」可点跳转（原型与自身不同才显示）
                    if (lemma != null && !lemma.equals(term, ignoreCase = true)) {
                        item { ListHeader { Text("词形变化") } }
                        item {
                            ClickableFormRow(
                                label = "原型",
                                word = lemma,
                                onClick = { onOpenWord(lemma) }
                            )
                        }
                        // 该词自身相对原型的关系（如 dying → die 的现在分词）
                        exchangeInfo.selfRelation?.let { rel ->
                            val relText = rel.map { DictRepository.EXCHANGE_LABELS[it.toString()] ?: it.toString() }
                                .distinct().joinToString("、")
                            item {
                                Text(
                                    text = "本词是 $lemma 的 $relText",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    // 原型词：列出自身各变形（可点跳转）
                    val forms = exchangeInfo.forms
                    if (forms.isNotEmpty() && (lemma == null || lemma.equals(term, ignoreCase = true))) {
                        if (lemma == null) item { ListHeader { Text("词形变化") } }
                        forms.forEach { (code, word) ->
                            item {
                                ClickableFormRow(
                                    label = DictRepository.EXCHANGE_LABELS[code] ?: code,
                                    word = word,
                                    onClick = { onOpenWord(word) }
                                )
                            }
                        }
                    }
                    // 变形家族：反向查到的其它变形（如 die → dying/dies/died）
                    if (state.inflections.isNotEmpty()) {
                        item { ListHeader { Text("变形") } }
                        state.inflections.forEach { infl ->
                            item {
                                ClickableFormRow(
                                    label = "",
                                    word = infl.term,
                                    onClick = { onOpenWord(infl.term) }
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
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
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
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 1.dp)
                        )
                    }
                    state.bookWord?.exampleTranslation?.let { translation ->
                        item {
                            Text(
                                text = translation,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                // 关联短语：详情页最底部。长按条目跳转到该短语的「详情」（以短语为词查）。
                if (state.phrases.isNotEmpty()) {
                    item { ListHeader { Text("关联短语") } }
                    state.phrases.forEach { p ->
                        item {
                            PhraseRow(
                                phrase = p.phrase,
                                zh = p.zh,
                                onLongClick = { onOpenWord(p.phrase) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 词形变化的可点行：「过去式  played」，点词跳转。 */
@Composable
private fun ClickableFormRow(label: String, word: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = word,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = if (label.isBlank()) 0.dp else 8.dp)
        )
    }
}

/** 关联短语行：短语 + 中文释义。长按跳转（避免和滚动/点详情冲突）。 */
@Composable
private fun PhraseRow(phrase: String, zh: String, onLongClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(phrase, style = MaterialTheme.typography.bodyMedium)
        if (zh.isNotBlank()) {
            Text(
                zh,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
private fun LazyListScope.definitionSection(title: String, lines: List<String>) {
    item { ListHeader { Text(title) } }
    lines.filter { it.isNotBlank() }.forEach { line ->
        item {
            Text(
                text = line.trim(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 1.dp)
            )
        }
    }
}
