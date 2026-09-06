package com.example.vocabwatch.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vocabwatch.domain.model.DefinitionDisplay
import com.example.vocabwatch.domain.model.StudyQueueConfig
import com.example.vocabwatch.domain.repository.SettingsRepository
import com.example.vocabwatch.reminder.ReminderScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StudySettingsUiState(
    val dailyNewLimit: Int = StudyQueueConfig.DEFAULT.dailyNewLimit,
    val definitionDisplay: DefinitionDisplay = DefinitionDisplay.BOTH,
    val reminderMinutes: Int? = null
)

class StudySettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val appContext: Context
) : ViewModel() {

    val uiState: StateFlow<StudySettingsUiState> = combine(
        settingsRepository.queueConfig,
        settingsRepository.definitionDisplay,
        settingsRepository.reminderMinutes
    ) { config, display, reminder ->
        StudySettingsUiState(
            dailyNewLimit = config.dailyNewLimit,
            definitionDisplay = display,
            reminderMinutes = reminder
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StudySettingsUiState())

    fun setDailyNew(limit: Int) {
        viewModelScope.launch { settingsRepository.setDailyNewLimit(limit) }
    }

    /** 详情页释义显示：中文 → 英文 → 中英 循环。 */
    fun cycleDefinitionDisplay() {
        viewModelScope.launch {
            val next = when (uiState.value.definitionDisplay) {
                DefinitionDisplay.ZH -> DefinitionDisplay.EN
                DefinitionDisplay.EN -> DefinitionDisplay.BOTH
                DefinitionDisplay.BOTH -> DefinitionDisplay.ZH
            }
            settingsRepository.setDefinitionDisplay(next)
        }
    }

    /** 开 / 关每日提醒；闹钟立即重排。 */
    fun setReminder(minutesOfDay: Int?) {
        viewModelScope.launch {
            settingsRepository.setReminderMinutes(minutesOfDay)
            ReminderScheduler.schedule(appContext, minutesOfDay)
        }
    }
}
