package com.example.vocabwatch.ui.mistakes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vocabwatch.data.dictionary.DictRepository
import com.example.vocabwatch.domain.repository.StudyRepository
import com.example.vocabwatch.data.local.dao.MistakeDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 随刷随学的一张卡：正面英文，背面中文。 */
data class DrillCard(
    val term: String,
    val phonetic: String?,
    val meaning: String?
)

sealed interface DrillUiState {
    data object Loading : DrillUiState

    /** 错题集为空，没什么可刷。 */
    data object Empty : DrillUiState

    data class Drill(
        val card: DrillCard,
        val flipped: Boolean,
        /** 含当前这张，还剩多少张（不会的会循环回来）。 */
        val remaining: Int,
        val knownCount: Int,
        /** 每次发射自增：不认识的词会循环回来，动画 key 必须变化 */
        val seq: Long
    ) : DrillUiState

    data class Finished(
        val knownCount: Int,
        /** 这轮里答「不会」过的词数：已排到明天复习。 */
        val lapsedCount: Int
    ) : DrillUiState
}

/**
 * 错题本「随刷随学」：把错题词洗牌后过一轮 英文→中文。
 *
 * - 只过一遍：正面英文，点开中文，左滑认识就完成，上滑不认识沉底循环
 * - 每次进入都重新洗牌（新 VM 实例 = 新顺序）
 * - 纯内存会话，不持久化进度，不写调度数据
 */
class MistakesDrillViewModel(
    private val mistakeDao: MistakeDao,
    private val dictRepository: DictRepository,
    private val studyRepository: StudyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<DrillUiState>(DrillUiState.Loading)
    val uiState: StateFlow<DrillUiState> = _uiState.asStateFlow()

    private val queue = ArrayDeque<DrillCard>()
    private var knownCount = 0
    private var seq = 0L

    /** 这轮里答「不会」的词：结束时移出错题集并排到明天复习。 */
    private val lapsedToday = LinkedHashSet<String>()

    init {
        reload()
    }

    /** 每次进入都重新加载并洗牌。 */
    fun reload() {
        viewModelScope.launch {
            _uiState.value = DrillUiState.Loading
            val mistakes = mistakeDao.observeAll().first()
            val cards = mistakes.map { m ->
                DrillCard(
                    term = m.term,
                    phonetic = m.phonetic,
                    meaning = m.meaning
                        ?: dictRepository.lookup(m.term)?.meaningZh?.lineSequence()?.firstOrNull()
                )
            }
            if (cards.isEmpty()) {
                _uiState.value = DrillUiState.Empty
                return@launch
            }
            queue.clear()
            queue.addAll(cards.shuffled())
            knownCount = 0
            emitCard()
        }
    }

    /** 点击翻面（拼写没有，这里就是英文→中文）。 */
    fun flip() {
        val state = _uiState.value as? DrillUiState.Drill ?: return
        if (!state.flipped) {
            _uiState.value = state.copy(flipped = true)
        }
    }

    /** 左滑认识 = 完成；上滑不认识 = 沉底循环。 */
    fun answer(known: Boolean) {
        val state = _uiState.value as? DrillUiState.Drill ?: return
        if (!state.flipped) return
        val card = queue.removeFirst()
        if (known) {
            knownCount++
        } else {
            lapsedToday.add(card.term)
            queue.addLast(card)
        }
        if (queue.isEmpty()) {
            finish()
        } else {
            emitCard()
        }
    }

    /**
     * 刷完一轮：答「不会」过的词排到明天复习；错题本列表本身不动
     * （删除由用户在错题本里手动进行，防误触）。
     */
    private fun finish() {
        viewModelScope.launch {
            lapsedToday.forEach { term -> runCatching { studyRepository.rescheduleToTomorrowByTerm(term) } }
            _uiState.value = DrillUiState.Finished(knownCount = knownCount, lapsedCount = lapsedToday.size)
        }
    }

    private fun emitCard() {
        val known = (uiState.value as? DrillUiState.Drill)?.knownCount ?: 0
        seq++
        _uiState.value = DrillUiState.Drill(
            card = queue.first(),
            flipped = false,
            remaining = queue.size,
            knownCount = known,
            seq = seq
        )
    }
}
