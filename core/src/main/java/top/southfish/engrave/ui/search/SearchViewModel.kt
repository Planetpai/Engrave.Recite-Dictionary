package top.southfish.engrave.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.southfish.engrave.data.dictionary.DictRepository
import top.southfish.engrave.data.local.dao.MistakeDao
import top.southfish.engrave.data.local.dao.SearchHistoryDao
import top.southfish.engrave.data.local.entity.SearchHistoryEntity
import top.southfish.engrave.domain.model.DictEntry
import top.southfish.engrave.domain.model.PhraseEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<SearchWordResult> = emptyList(),
    /** 与单词混合呈现的短语结果（带「词组」标识）。 */
    val phrases: List<PhraseEntry> = emptyList(),
    /** 查询为空时展示的历史记录。 */
    val history: List<SearchHistoryEntity> = emptyList()
)

/**
 * 搜索结果条目。一个词可能出两条：
 * - 正常词条（inflectionHint=null）：词名 + 音标 + 释义首行，点击进本词详情；
 * - 变形标注条（inflectionHint 非空）：同词名，无音标无释义，第二行灰色小字
 *   标注原型关系（如「die 的现在分词」），点击进原型详情。
 * 变形条的标注来自该词条的 exchange 字段，不依赖原型词条存在于词库。
 */
data class SearchWordResult(
    val entry: DictEntry,
    val inflectionHint: String? = null,
    /** 点击跳转目标：正常条=本词，变形条=原型词。 */
    val clickTarget: String = entry.term
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val dictRepository: DictRepository,
    private val searchDao: SearchHistoryDao,
    private val mistakeDao: MistakeDao
) : ViewModel() {

    private val query = MutableStateFlow("")

    init {
        // 词典子集首次要从 assets 拷到 filesDir，进页面就备好，输入时零等待
        viewModelScope.launch { runCatching { dictRepository.ensureReady() } }
    }

    // 防抖后的查询：展示文本和实际检索都用它。旧实现检索用的是未防抖的原始
    // flow，每个按键都打 2~4 条 LIKE（虽然 flatMapLatest 会取消旧查询，
    // 但 DB 调用已经发出）；现在停顿 200ms 才真正查库。
    private val debouncedQuery = query.debounce(SEARCH_DEBOUNCE_MS)

    val uiState: StateFlow<SearchUiState> = combine(
        debouncedQuery,
        debouncedQuery.flatMapLatest { q ->
            if (q.isBlank()) {
                flowOf(emptyList<SearchWordResult>() to emptyList<PhraseEntry>())
            } else {
                // 本地 sqlite 查询，直接出结果；空列表就是“没有匹配”
                flowOf(assembleWordResults(dictRepository.search(q)) to dictRepository.searchPhrases(q))
            }
        },
        searchDao.observeRecent(HISTORY_LIMIT)
    ) { q, (results, phrases), history ->
        SearchUiState(
            query = q,
            results = results,
            phrases = phrases,
            history = history
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    /** 点开一个词：写历史再跳详情。 */
    fun openWord(term: String, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val entry = dictRepository.lookup(term)
                searchDao.upsert(
                    SearchHistoryEntity(
                        term = entry?.term ?: term,
                        phonetic = entry?.phonetic,
                        meaning = entry?.meaningZh?.lineSequence()?.firstOrNull(),
                        lookedUpAt = System.currentTimeMillis()
                    )
                )
            }
            onOpened(term)
        }
    }

    /** 历史记录左滑：加入错题集并把这条历史清掉。 */
    fun addMistakeFromHistory(term: String) {
        viewModelScope.launch {
            runCatching {
                val entry = dictRepository.lookup(term)
                top.southfish.engrave.data.local.entity.MistakeEntity(
                    term = term,
                    meaning = entry?.meaningZh?.lineSequence()?.firstOrNull(),
                    phonetic = entry?.phonetic,
                    addedAt = System.currentTimeMillis()
                ).let { mistakeDao.upsert(it) }
                searchDao.delete(term)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { searchDao.clear() }
    }

    /**
     * 组装单词结果：每条词条原样保留；若它是变形词（exchange 含 0:原型 且
     * 原型≠自身），紧随其后追加一条「变形标注条」——同词名、标注原型关系、
     * 点击跳原型详情。搜原型词不带出变形词（原型 exchange 里没有 0: 段）。
     */
    private fun assembleWordResults(entries: List<DictEntry>): List<SearchWordResult> {
        val out = ArrayList<SearchWordResult>(entries.size + 4)
        for (entry in entries) {
            out.add(SearchWordResult(entry))
            val parsed = DictRepository.parseExchange(entry.exchange) ?: continue
            val lemma = parsed.lemma ?: continue
            if (lemma.equals(entry.term, ignoreCase = true)) continue
            val relText = parsed.selfRelation
                ?.map { DictRepository.EXCHANGE_LABELS[it.toString()] ?: it.toString() }
                ?.distinct()?.joinToString("、")
            val hint = if (relText.isNullOrBlank()) "$lemma 的变形" else "$lemma 的$relText"
            out.add(SearchWordResult(entry, inflectionHint = hint, clickTarget = lemma))
        }
        return out
    }

    private companion object {
        const val HISTORY_LIMIT = 20
        const val SEARCH_DEBOUNCE_MS = 200L
    }
}
