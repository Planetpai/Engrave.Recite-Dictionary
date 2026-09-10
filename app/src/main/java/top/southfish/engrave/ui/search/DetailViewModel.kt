package top.southfish.engrave.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.southfish.engrave.data.dictionary.DictRepository
import top.southfish.engrave.data.local.dao.MistakeDao
import top.southfish.engrave.domain.model.DefinitionDisplay
import top.southfish.engrave.domain.model.DictEntry
import top.southfish.engrave.domain.model.Word
import top.southfish.engrave.domain.repository.SettingsRepository
import top.southfish.engrave.domain.repository.WordRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WordDetailUiState(
    val entry: DictEntry? = null,
    val bookWord: Word? = null,
    val inMistakes: Boolean = false,
    val display: DefinitionDisplay = DefinitionDisplay.BOTH,
    /** 同根派生词（different → differently / difference …），带各自释义。 */
    val derivatives: List<DictEntry> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class WordDetailViewModel(
    term: String,
    private val dictRepository: DictRepository,
    private val mistakeDao: MistakeDao,
    private val wordRepository: WordRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val termFlow = kotlinx.coroutines.flow.MutableStateFlow(term)

    val uiState: StateFlow<WordDetailUiState> = termFlow
        .flatMapLatest { t ->
            combine(
                settingsRepository.definitionDisplay,
                mistakeDao.observeExists(t)
            ) { display, inMistakes ->
                Triple(display, inMistakes, t)
            }
        }
        .flatMapLatest { (display, inMistakes, t) ->
            kotlinx.coroutines.flow.flow {
                emit(WordDetailUiState(display = display, inMistakes = inMistakes))
                // 词典 lookup 和词书查找互不依赖，并行跑（旧实现串行 await）
                val (entry, bookWord) = kotlinx.coroutines.coroutineScope {
                    val entryDeferred = async {
                        runCatching { dictRepository.lookup(t) }.getOrNull()
                    }
                    val bookDeferred = async {
                        runCatching { wordRepository.findWord(t) }.getOrNull()
                    }
                    entryDeferred.await() to bookDeferred.await()
                }
                emit(
                    WordDetailUiState(
                        entry = entry,
                        bookWord = bookWord,
                        inMistakes = inMistakes,
                        display = display
                    )
                )
                val derivatives = runCatching { dictRepository.derivatives(t) }.getOrDefault(emptyList())
                emit(
                    WordDetailUiState(
                        entry = entry,
                        bookWord = bookWord,
                        inMistakes = inMistakes,
                        display = display,
                        derivatives = derivatives
                    )
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WordDetailUiState())

    fun toggleMistake(term: String) {
        viewModelScope.launch {
            if (mistakeDao.exists(term)) {
                mistakeDao.delete(term)
            } else {
                val entry = uiState.value.entry
                mistakeDao.upsert(
                    top.southfish.engrave.data.local.entity.MistakeEntity(
                        term = term,
                        meaning = entry?.meaningZh?.lineSequence()?.firstOrNull(),
                        phonetic = entry?.phonetic,
                        addedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }
}
