package top.southfish.engrave.ui.search

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import top.southfish.engrave.ui.components.Chip
import top.southfish.engrave.ui.components.ChipDefaults
import top.southfish.engrave.ui.components.ListHeader
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
    // 在列表 DSL 外算好词形变换（DSL lambda 不是 composable 上下文）
    val forms = rememberForms(state.entry?.exchange)

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

                if (forms.isNotEmpty()) {
                    item { ListHeader { Text("词形变换") } }
                    forms.forEach { (label, value) ->
                        item {
                            Text(
                                text = "$label  $value",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 1.dp)
                            )
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
            }
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

/**
 * exchange 列是 `d:did/p:done/3:does` 这种键值串，翻译成中文标签。
 *
 * ECDICT 键位约定：p=过去式、d=过去分词（p:went/d:gone）、i=现在分词、
 * 3=三单、r=比较级、t=最高级、s=复数、0=原形。特殊键 `1` 的值不是单词
 * 而是「本词是原形的哪种形式」的类别码（可多字母组合，如 dp=过去式+
 * 过去分词），出现该键的多为派生词（starting→0:start/1:i），直接渲染
 * 会露出原始编码（"1 i"/"1 p"），这里翻译成中文。
 */
@Composable
private fun rememberForms(exchange: String?): List<Pair<String, String>> =
    remember(exchange) {
        val labels = mapOf(
            "0" to "原形", "p" to "过去式", "d" to "过去分词", "i" to "现在分词",
            "3" to "三单", "r" to "比较级", "t" to "最高级", "s" to "复数"
        )
        exchange?.split("/")?.mapNotNull { part ->
            val key = part.substringBefore(":").trim()
            val value = part.substringAfter(":", "").trim()
            if (value.isBlank()) null
            else if (key == "1") {
                // 类别码逐字母翻译，未知字母原样保留
                val kinds = value.map { labels[it.toString()] ?: it.toString() }
                "形式" to kinds.distinct().joinToString("、")
            } else (labels[key] ?: key) to value
        }.orEmpty()
    }
