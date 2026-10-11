package top.southfish.engrave.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.southfish.engrave.data.tts.TtsSpeaker
import top.southfish.engrave.domain.model.DefinitionDisplay
import top.southfish.engrave.domain.model.StudyQueueConfig
import top.southfish.engrave.domain.repository.SettingsRepository
import top.southfish.engrave.reminder.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StudySettingsUiState(
    val dailyNewLimit: Int = StudyQueueConfig.DEFAULT.dailyNewLimit,
    val definitionDisplay: DefinitionDisplay = DefinitionDisplay.BOTH,
    val reminderMinutes: Int? = null,
    /** 单词发音：仅控制学习卡片展示英文词头时是否自动朗读，默认关闭 */
    val ttsEnabled: Boolean = false,
    /** 系统 TTS 可用性：null=检测中，false=不可用（禁止开启，页面显示提示） */
    val ttsAvailable: Boolean? = null,
    /** DataStore 首次读取完成前为 false；加载前不能把默认值当用户输入回写 */
    val loaded: Boolean = false
)

class StudySettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val appContext: Context,
    private val ttsSpeaker: TtsSpeaker
) : ViewModel() {

    /** TTS 可用性是异步检测结果，不走 DataStore，单独持有。 */
    private val ttsAvailable = MutableStateFlow<Boolean?>(null)

    init {
        ttsSpeaker.checkAvailable { available ->
            // 回调在 TTS 引擎线程，切回主作用域再发流
            viewModelScope.launch { ttsAvailable.value = available }
        }
    }

    val uiState: StateFlow<StudySettingsUiState> = combine(
        settingsRepository.queueConfig,
        settingsRepository.definitionDisplay,
        settingsRepository.reminderMinutes,
        settingsRepository.ttsEnabled,
        ttsAvailable
    ) { config, display, reminder, tts, available ->
        StudySettingsUiState(
            dailyNewLimit = config.dailyNewLimit,
            definitionDisplay = display,
            reminderMinutes = reminder,
            ttsEnabled = tts,
            ttsAvailable = available,
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

    /**
     * 单词发音开关：仅控制学习卡片展示英文词头时是否自动朗读。
     * 检测确认 TTS 不可用时拒绝开启；历史已开启的配置不静默关闭，由页面提示。
     */
    fun toggleTts() {
        val current = uiState.value
        if (!current.ttsEnabled && current.ttsAvailable == false) return
        viewModelScope.launch { settingsRepository.setTtsEnabled(!current.ttsEnabled) }
    }

    /** 开 / 关每日提醒；闹钟立即重排。 */
    fun setReminder(minutesOfDay: Int?) {
        viewModelScope.launch {
            settingsRepository.setReminderMinutes(minutesOfDay)
            ReminderScheduler.schedule(appContext, minutesOfDay)
        }
    }
}
