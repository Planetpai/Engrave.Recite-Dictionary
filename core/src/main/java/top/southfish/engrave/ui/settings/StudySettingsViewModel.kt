package top.southfish.engrave.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.southfish.engrave.domain.model.DefinitionDisplay
import top.southfish.engrave.domain.model.StudyQueueConfig
import top.southfish.engrave.domain.repository.SettingsRepository
import top.southfish.engrave.reminder.ReminderScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StudySettingsUiState(
    val dailyNewLimit: Int = StudyQueueConfig.DEFAULT.dailyNewLimit,
    val definitionDisplay: DefinitionDisplay = DefinitionDisplay.BOTH,
    val reminderMinutes: Int? = null,
    /** 单词发音：开启时详情页显示朗读喇叭 */
    val ttsEnabled: Boolean = true,
    /** DataStore 首次读取完成前为 false；加载前不能把默认值当用户输入回写 */
    val loaded: Boolean = false
)

class StudySettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val appContext: Context
) : ViewModel() {

    val uiState: StateFlow<StudySettingsUiState> = combine(
        settingsRepository.queueConfig,
        settingsRepository.definitionDisplay,
        settingsRepository.reminderMinutes,
        settingsRepository.ttsEnabled
    ) { config, display, reminder, tts ->
        StudySettingsUiState(
            dailyNewLimit = config.dailyNewLimit,
            definitionDisplay = display,
            reminderMinutes = reminder,
            ttsEnabled = tts,
            loaded = true
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

    /** 单词发音开关：关掉后详情页不再显示喇叭。 */
    fun toggleTts() {
        viewModelScope.launch { settingsRepository.setTtsEnabled(!uiState.value.ttsEnabled) }
    }

    /** 开 / 关每日提醒；闹钟立即重排。 */
    fun setReminder(minutesOfDay: Int?) {
        viewModelScope.launch {
            settingsRepository.setReminderMinutes(minutesOfDay)
            ReminderScheduler.schedule(appContext, minutesOfDay)
        }
    }
}
