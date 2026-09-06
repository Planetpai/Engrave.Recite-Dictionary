package com.example.vocabwatch.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vocabwatch.data.dictionary.DictRepository
import com.example.vocabwatch.data.local.dao.MistakeDao
import com.example.vocabwatch.data.local.dao.SearchHistoryDao
import com.example.vocabwatch.data.local.entity.SearchHistoryEntity
import com.example.vocabwatch.domain.model.DictEntry
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
    val results: List<DictEntry> = emptyList(),
    /** 查询为空时展示的历史记录。 */
    val history: List<SearchHistoryEntity> = emptyList(),
    val searching: Boolean = false
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

    val uiState: StateFlow<SearchUiState> = combine(
        query.debounce(200),
        query.flatMapLatest { q ->
            if (q.isBlank()) {
                flowOf(emptyList())
            } else {
                kotlinx.coroutines.flow.flow {
                    emit(emptyList<DictEntry>())
                    emit(dictRepository.search(q))
                }
            }
        },
        searchDao.observeRecent(HISTORY_LIMIT)
    ) { q, results, history ->
        SearchUiState(
            query = q,
            results = results,
            history = history,
            searching = q.isNotBlank() && results.isEmpty()
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
                com.example.vocabwatch.data.local.entity.MistakeEntity(
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

    private companion object {
        const val HISTORY_LIMIT = 20
    }
}
