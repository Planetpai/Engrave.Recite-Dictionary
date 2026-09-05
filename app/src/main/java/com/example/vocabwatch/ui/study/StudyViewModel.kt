package com.example.vocabwatch.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vocabwatch.domain.model.Grade
import com.example.vocabwatch.domain.model.StudyItem
import com.example.vocabwatch.domain.repository.SettingsRepository
import com.example.vocabwatch.domain.srs.Scheduler
import com.example.vocabwatch.domain.srs.TimeProvider
import com.example.vocabwatch.domain.usecase.GetStudySessionUseCase
import com.example.vocabwatch.domain.usecase.SubmitReviewUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface StudyUiState {
    data object Loading : StudyUiState
    data object Empty : StudyUiState

    data class Card(
        val item: StudyItem,
        val index: Int,
        val total: Int,
        val revealed: Boolean,
        /** 每档评分对应的下次间隔（天），用于在按钮上预告「1 天后」 */
        val intervalPreviews: Map<Grade, Double>
    ) : StudyUiState

    data class Finished(
        val reviewedCount: Int,
        val graduatedCount: Int
    ) : StudyUiState
}

class StudyViewModel(
    private val getStudySession: GetStudySessionUseCase,
    private val submitReview: SubmitReviewUseCase,
    private val settingsRepository: SettingsRepository,
    private val scheduler: Scheduler,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow<StudyUiState>(StudyUiState.Loading)
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

    /**
     * 会话队列是内存里的快照，不是 Flow。
     * 若直接观察数据库，刚答对的卡片会因为 dueAt 变化而立刻从队列里消失，
     * 导致进度显示和翻页错乱。
     */
    private var queue: List<StudyItem> = emptyList()
    private var cursor: Int = 0
    private var reviewedCount: Int = 0
    private var graduatedCount: Int = 0
    private var cardShownAt: Long = 0L

    init {
        startSession()
    }

    fun startSession() {
        viewModelScope.launch {
            _uiState.value = StudyUiState.Loading
            val config = settingsRepository.queueConfig.first()
            queue = getStudySession(config)
            cursor = 0
            reviewedCount = 0
            graduatedCount = 0
            _uiState.value = if (queue.isEmpty()) {
                StudyUiState.Empty
            } else {
                buildCardState()
            }
        }
    }

    fun reveal() {
        val card = _uiState.value as? StudyUiState.Card ?: return
        if (!card.revealed) {
            _uiState.value = card.copy(revealed = true)
        }
    }

    fun grade(grade: Grade) {
        val card = _uiState.value as? StudyUiState.Card ?: return
        val item = card.item
        val elapsedMillis = (timeProvider.now() - cardShownAt).coerceAtLeast(0L)

        viewModelScope.launch {
            val outcome = submitReview(item, grade, elapsedMillis)
            reviewedCount++
            if (outcome.graduated) graduatedCount++

            cursor++
            _uiState.value = if (cursor >= queue.size) {
                StudyUiState.Finished(reviewedCount, graduatedCount)
            } else {
                buildCardState()
            }
        }
    }

    private fun buildCardState(): StudyUiState.Card {
        val item = queue[cursor]
        cardShownAt = timeProvider.now()

        // Scheduler 是纯函数，所以四档评分的结果可以提前全算出来给 UI 预告间隔。
        // 换成 FSRS 之类更重的算法后，这里也只是多几次内存计算。
        val now = cardShownAt
        val previews = Grade.values().associateWith { grade ->
            scheduler.schedule(item.state, grade, now).scheduledInDays
        }

        return StudyUiState.Card(
            item = item,
            index = cursor,
            total = queue.size,
            revealed = false,
            intervalPreviews = previews
        )
    }
}
