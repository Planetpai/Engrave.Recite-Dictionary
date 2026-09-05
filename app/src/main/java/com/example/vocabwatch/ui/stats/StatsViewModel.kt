package com.example.vocabwatch.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vocabwatch.domain.model.StudyStats
import com.example.vocabwatch.domain.model.WordBook
import com.example.vocabwatch.domain.repository.SettingsRepository
import com.example.vocabwatch.domain.repository.StudyRepository
import com.example.vocabwatch.domain.repository.WordRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StatsUiState(
    val isLoading: Boolean = true,
    val stats: StudyStats = StudyStats(),
    val books: List<WordBook> = emptyList(),
    val activeBookId: Long? = null,
    val dailyNewLimit: Int = 0,
    val dailyReviewLimit: Int = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    studyRepository: StudyRepository,
    wordRepository: WordRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = settingsRepository.queueConfig
        .flatMapLatest { config ->
            combine(
                studyRepository.observeStats(config.activeBookId),
                wordRepository.observeBooks()
            ) { stats, books ->
                StatsUiState(
                    isLoading = false,
                    stats = stats,
                    books = books,
                    activeBookId = config.activeBookId,
                    dailyNewLimit = config.dailyNewLimit,
                    dailyReviewLimit = config.dailyReviewLimit
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StatsUiState()
        )

    /** 切换当前学习的词书，null 表示全部词书一起学。 */
    fun selectBook(bookId: Long?) {
        viewModelScope.launch {
            settingsRepository.setActiveBook(bookId)
        }
    }
}
