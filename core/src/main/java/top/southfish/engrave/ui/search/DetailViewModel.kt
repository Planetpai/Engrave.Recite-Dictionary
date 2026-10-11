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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val derivatives: List<DictEntry> = emptyList(),
    /** 词形变化信息：原型 + 各变形（由 exchange 解出）。 */
    val exchangeInfo: ExchangeInfo? = null,
    /** 词头作为短语命中时的短语数据（单词查不到时按短语详情渲染）。 */
    val phrase: PhraseEntry? = null,
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

    // 详情页被更深层路由（派生词/短语详情）覆盖时组合被销毁，rememberSaveable
    // 在多层跳转后恢复不可靠；滚动位置存在 VM（挂在导航条目上，返回时仍存活）。
    private var scrollPos: ListScrollPos = ListScrollPos()
    fun scrollPos(): ListScrollPos = scrollPos
    fun saveScrollPos(pos: ListScrollPos) {
        scrollPos = pos
    }

    val uiState: StateFlow<WordDetailUiState> = termFlow
        .flatMapLatest { t ->
            combine(
                settingsRepository.definitionDisplay,
                mistakeDao.observeExists(t)
            ) { display, inMistakes ->
                Gate(display, inMistakes, t)
            }
        }
        // 导航栈记录器每次跳转都写同一个 DataStore，会让上面的偏好流重发
        // 相同值；Gate 不变就绝不能重启下面的词典加载流——否则先 emit 空态、
        // 列表 item 骤减，ScalingLazyColumn 会把已恢复的居中项钳回顶部。
        .distinctUntilChanged()
        .flatMapLatest { gate ->
            kotlinx.coroutines.flow.flow {
                emit(
                    WordDetailUiState(
                        display = gate.display,
                        inMistakes = gate.inMistakes
                    )
                )
                // 词典 lookup、短语精确查、词书查找互不依赖，并行跑
                val (entry, phrase, bookWord) = kotlinx.coroutines.coroutineScope {
                    val entryDeferred = async {
                        runCatching { dictRepository.lookup(gate.term) }.getOrNull()
                    }
                    val phraseDeferred = async {
                        runCatching { dictRepository.lookupPhrase(gate.term) }.getOrNull()
                    }
                    val bookDeferred = async {
                        runCatching { wordRepository.findWord(gate.term) }.getOrNull()
                    }
                    Triple(entryDeferred.await(), phraseDeferred.await(), bookDeferred.await())
                }
                emit(
                    WordDetailUiState(
                        entry = entry,
                        phrase = phrase,
                        bookWord = bookWord,
                        inMistakes = gate.inMistakes,
                        display = gate.display
                    )
                )
                val derivatives = runCatching { dictRepository.derivatives(gate.term) }.getOrDefault(emptyList())
                // 词形变化（原型+变形）与关联短语并行，和主释义/派生词互不依赖
                val (exchangeInfo, phrases) = kotlinx.coroutines.coroutineScope {
                    val exDeferred = async { runCatching { dictRepository.exchangeInfo(gate.term) }.getOrNull() }
                    val phrDeferred = async { runCatching { dictRepository.relatedPhrases(gate.term) }.getOrDefault(emptyList()) }
                    exDeferred.await() to phrDeferred.await()
                }
                emit(
                    WordDetailUiState(
                        entry = entry,
                        phrase = phrase,
                        bookWord = bookWord,
                        inMistakes = gate.inMistakes,
                        display = gate.display,
                        derivatives = derivatives,
                        exchangeInfo = exchangeInfo,
                        phrases = phrases
                    )
                )
            }
        }
        // 必须用 Eagerly 而不是 WhileSubscribed：详情页被更深路由覆盖时组合
        // 销毁、订阅中断，一旦超时整条冷流重启，新流的第一个 Gate 没有历史
        // 可比、distinctUntilChanged 挡不住，内层仍会先 emit 空态、列表位置
        // 被钳回顶部。Eagerly 让链条随 VM（=导航条目）存活，配合上面的
        // distinctUntilChanged（挡 DataStore 写导航栈引发的相同 Gate 重放）
        // 共同保证详情数据不闪空；条目出栈时 viewModelScope 自动取消，无泄漏。
        .stateIn(viewModelScope, SharingStarted.Eagerly, WordDetailUiState())

    fun toggleMistake(term: String) {
        viewModelScope.launch {
            if (mistakeDao.exists(term)) {
                mistakeDao.delete(term)
            } else {
                val entry = uiState.value.entry
                mistakeDao.upsert(
                    top.southfish.engrave.data.local.entity.MistakeEntity(
                        term = term,
                        meaning = entry?.meaningZh?.lineSequence()?.firstOrNull()
                            ?: uiState.value.phrase?.zh?.lineSequence()?.firstOrNull(),
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

/** 详情页输入汇总：显示偏好 + 错题状态 + 当前词头。 */
private data class Gate(
    val display: DefinitionDisplay,
    val inMistakes: Boolean,
    val term: String
)
