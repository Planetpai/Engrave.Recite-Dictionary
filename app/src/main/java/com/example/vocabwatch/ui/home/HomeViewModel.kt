package com.example.vocabwatch.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vocabwatch.domain.model.StudyQueueConfig
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

data class HomeUiState(
    val isLoading: Boolean = true,
    val stats: StudyStats = StudyStats(),
    val books: List<WordBook> = emptyList(),
    val activeBookName: String? = null,
    val dailyNewLimit: Int = StudyQueueConfig.DEFAULT.dailyNewLimit
) {
    /** 今天还能学几个新词。 */
    val newRemainingToday: Int
        get() = minOf(stats.newRemaining, dailyNewLimit)

    /** 这次点「开始学习」大概会拿到多少张卡。 */
    val sessionSize: Int
        get() = minOf(stats.dueNow + newRemainingToday, 20)

    val hasAnythingToStudy: Boolean
        get() = stats.dueNow > 0 || newRemainingToday > 0
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    studyRepository: StudyRepository,
    settingsRepository: SettingsRepository,
    wordRepository: WordRepository
) : ViewModel() {

    /**
     * 设置里的 activeBookId 决定统计范围，所以要先拿到设置再 flatMapLatest
     * 切换到对应的统计流。用户改了词书，首页数字会立刻跟着变。
     */
    val uiState: StateFlow<HomeUiState> = settingsRepository.queueConfig
        .flatMapLatest { config ->
            combine(
                studyRepository.observeStats(config.activeBookId),
                wordRepository.observeBooks()
            ) { stats, books ->
                HomeUiState(
                    isLoading = false,
                    stats = stats,
                    books = books,
                    activeBookName = books.firstOrNull { it.id == config.activeBookId }?.name,
                    dailyNewLimit = config.dailyNewLimit
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            // 手表上应用经常被切到后台，停 5 秒后停止上游收集省电
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState()
        )
}
