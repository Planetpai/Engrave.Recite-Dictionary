package top.southfish.engrave.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.southfish.engrave.data.dictionary.DictRepository
import top.southfish.engrave.data.local.dao.MistakeDao
import top.southfish.engrave.data.tts.TtsSpeaker
import top.southfish.engrave.domain.model.DefinitionDisplay
import top.southfish.engrave.domain.model.DictEntry
import top.southfish.engrave.domain.model.ExchangeInfo
import top.southfish.engrave.domain.model.PhraseEntry
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
    /** 发音开关：关闭时详情页不显示喇叭按钮。 */
    val ttsEnabled: Boolean = false,
    /** 同根派生词（different → differently / difference …），带各自释义。 */
    val derivatives: List<DictEntry> = emptyList(),
    /** 词形变化信息：原型 + 各变形（由 exchange 解出）。 */
    val exchangeInfo: ExchangeInfo? = null,
    /** 反向查到的变形家族（若该词是原型）。 */
    val inflections: List<DictEntry> = emptyList(),
    /** 关联短语（挂在该词下的短语）。 */
    val phrases: List<PhraseEntry> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class WordDetailViewModel(
    term: String,
    private val dictRepository: DictRepository,
    private val mistakeDao: MistakeDao,
    private val wordRepository: WordRepository,
    private val settingsRepository: SettingsRepository,
    private val ttsSpeaker: TtsSpeaker
) : ViewModel() {

    private val termFlow = kotlinx.coroutines.flow.MutableStateFlow(term)

    val uiState: StateFlow<WordDetailUiState> = termFlow
        .flatMapLatest { t ->
            combine(
                settingsRepository.definitionDisplay,
                settingsRepository.ttsEnabled,
                mistakeDao.observeExists(t)
            ) { display, tts, inMistakes ->
                Gate(display, tts, inMistakes, t)
            }
        }
        .flatMapLatest { gate ->
            kotlinx.coroutines.flow.flow {
                emit(
                    WordDetailUiState(
                        display = gate.display,
                        inMistakes = gate.inMistakes,
                        ttsEnabled = gate.ttsEnabled
                    )
                )
                // 词典 lookup 和词书查找互不依赖，并行跑（旧实现串行 await）
                val (entry, bookWord) = kotlinx.coroutines.coroutineScope {
                    val entryDeferred = async {
                        runCatching { dictRepository.lookup(gate.term) }.getOrNull()
                    }
                    val bookDeferred = async {
                        runCatching { wordRepository.findWord(gate.term) }.getOrNull()
                    }
                    entryDeferred.await() to bookDeferred.await()
                }
                emit(
                    WordDetailUiState(
                        entry = entry,
                        bookWord = bookWord,
                        inMistakes = gate.inMistakes,
                        display = gate.display,
                        ttsEnabled = gate.ttsEnabled
                    )
                )
                val derivatives = runCatching { dictRepository.derivatives(gate.term) }.getOrDefault(emptyList())
                // 词形变化（原型+变形）与关联短语并行，和主释义/派生词互不依赖
                val (exchangeInfo, inflections, phrases) = kotlinx.coroutines.coroutineScope {
                    val exDeferred = async { runCatching { dictRepository.exchangeInfo(gate.term) }.getOrNull() }
                    val inflDeferred = async { runCatching { dictRepository.inflectionsOf(gate.term) }.getOrDefault(emptyList()) }
                    val phrDeferred = async { runCatching { dictRepository.relatedPhrases(gate.term) }.getOrDefault(emptyList()) }
                    Triple(exDeferred.await(), inflDeferred.await(), phrDeferred.await())
                }
                emit(
                    WordDetailUiState(
                        entry = entry,
                        bookWord = bookWord,
                        inMistakes = gate.inMistakes,
                        display = gate.display,
                        ttsEnabled = gate.ttsEnabled,
                        derivatives = derivatives,
                        exchangeInfo = exchangeInfo,
                        inflections = inflections,
                        phrases = phrases
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

    /** 朗读当前单词（系统 TTS）。 */
    fun speakTerm() {
        ttsSpeaker.speak(termFlow.value)
    }
}

/** 详情页输入汇总：显示偏好 + 发音开关 + 错题状态 + 当前词头。 */
private data class Gate(
    val display: DefinitionDisplay,
    val ttsEnabled: Boolean,
    val inMistakes: Boolean,
    val term: String
)
