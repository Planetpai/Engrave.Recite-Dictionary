package top.southfish.engrave.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.southfish.engrave.domain.model.StudyStats
import top.southfish.engrave.domain.repository.SettingsRepository
import top.southfish.engrave.domain.repository.StudyRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class StatsUiState(
    val isLoading: Boolean = true,
    val stats: StudyStats = StudyStats(),
    val dailyNewLimit: Int = 0,
)

/**
 * 学习统计页的数据。统计口径跟随当前启用的词书集合（词书管理页里多选，
 * 空集合 = 全部词书）；切换词书的入口只在词书管理页，避免两处重复。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    studyRepository: StudyRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = settingsRepository.queueConfig
        .flatMapLatest { config ->
            studyRepository.observeStats(config.activeBookIds).map { stats ->
                StatsUiState(
                    isLoading = false,
                    stats = stats,
                    dailyNewLimit = config.dailyNewLimit,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StatsUiState()
        )
}
