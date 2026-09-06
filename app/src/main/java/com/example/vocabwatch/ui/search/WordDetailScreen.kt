package com.example.vocabwatch.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.example.vocabwatch.domain.model.DefinitionDisplay
import com.example.vocabwatch.ui.components.rememberRotaryScrollable
import com.example.vocabwatch.ui.vocabViewModel

/**
 * 词详情：单词 + 音标 → 错题集开关 → 释义（按用户页的偏好显示中/英/中英）
 * → 词形变换（过去式/过去分词等）→ 例句（词书里有的话）。
 * 数据来自 ECDICT 离线词典，例句来自内置词书。
 */
@Composable
fun WordDetailScreen(
    term: String,
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
    val listState = rememberScalingLazyListState()
    rememberRotaryScrollable(listState)
    // 在列表 DSL 外算好词形变换（DSL lambda 不是 composable 上下文）
    val forms = rememberForms(state.entry?.exchange)

    Scaffold(
        modifier = modifier,
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = term,
                    style = MaterialTheme.typography.title1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
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
            if (entry == null) {
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
            } else {
                if (state.display != DefinitionDisplay.EN && !entry.meaningZh.isNullOrBlank()) {
                    definitionSection("释义", entry.meaningZh.split("\n"))
                }
                if (state.display != DefinitionDisplay.ZH && !entry.definitionEn.isNullOrBlank()) {
                    definitionSection("English", entry.definitionEn.split("\n"))
                }

                if (forms.isNotEmpty()) {
                    item { ListHeader { Text("词形变换") } }
                    item {
                        Column(Modifier.fillMaxWidth()) {
                            forms.forEach { (label, value) ->
                                Text(
                                    text = "$label  $value",
                                    style = MaterialTheme.typography.body2,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }

                if (state.derivatives.isNotEmpty()) {
                    item { ListHeader { Text("派生") } }
                    item {
                        Column(Modifier.fillMaxWidth()) {
                            state.derivatives.forEach { derived ->
                                Text(
                                    text = derivedLine(derived),
                                    style = MaterialTheme.typography.body2,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }

                state.bookWord?.example?.let { example ->
                    item { ListHeader { Text("例句") } }
                    item {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                text = example,
                                style = MaterialTheme.typography.body2,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 1.dp)
                            )
                            state.bookWord?.exampleTranslation?.let { translation ->
                                Text(
                                    text = translation,
                                    style = MaterialTheme.typography.caption2,
                                    color = MaterialTheme.colors.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 派生行：differently (adv.) 不同地。词性从释义开头推断（ECDICT 惯例）。 */
private fun derivedLine(entry: com.example.vocabwatch.domain.model.DictEntry): String {
    val firstLine = entry.meaningZh?.lineSequence()?.firstOrNull()?.trim()
    val pos = firstLine?.let { Regex("^([a-z]+\\.)\\s*").find(it)?.groupValues?.get(1) }
    val meaning = firstLine?.replaceFirst(Regex("^[a-z]+\\.\\s*"), "") ?: ""
    val tag = pos?.let { " [$it]" } ?: ""
    return entry.term + tag + if (meaning.isBlank()) "" else "  $meaning"
}

/** 释义块：小标题 + 逐行正文，空行丢弃。 */
private fun ScalingLazyListScope.definitionSection(title: String, lines: List<String>) {    item { ListHeader { Text(title) } }
    item {
        Column(Modifier.fillMaxWidth()) {
            lines.filter { it.isNotBlank() }.forEach { line ->
                Text(
                    text = line.trim(),
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 1.dp)
                )
            }
        }
    }
}

/** exchange 列是 `d:did/p:done/3:does` 这种键值串，翻译成中文标签。 */
@Composable
private fun rememberForms(exchange: String?): List<Pair<String, String>> =
    remember(exchange) {
        val labels = mapOf(
            "0" to "原形", "d" to "过去式", "p" to "过去分词", "i" to "现在分词",
            "3" to "三单", "r" to "比较级", "t" to "最高级", "s" to "复数"
        )
        exchange?.split("/")?.mapNotNull { part ->
            val key = part.substringBefore(":").trim()
            val value = part.substringAfter(":", "").trim()
            if (value.isBlank()) null else (labels[key] ?: key) to value
        }.orEmpty()
    }
