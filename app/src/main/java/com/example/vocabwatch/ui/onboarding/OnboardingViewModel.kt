package com.example.vocabwatch.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vocabwatch.domain.model.WordBook
import com.example.vocabwatch.domain.repository.SettingsRepository
import com.example.vocabwatch.domain.repository.WordRepository
import com.example.vocabwatch.reminder.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OnboardingUiState(
    /** 0 选词书 / 1 每日数量 / 2 提醒时间 / 3 完成 */
    val step: Int = 0,
    val books: List<WordBook> = emptyList(),
    val selectedBookId: Long? = null,
    val dailyNewLimit: Int = 10,
    val reminderMinutes: Int? = null,
    val saving: Boolean = false
) {
    val canNext: Boolean
        get() = when (step) {
            0 -> selectedBookId != null || books.isEmpty()
            2 -> true
            else -> true
        }
}

class OnboardingViewModel(
    private val settingsRepository: SettingsRepository,
    wordRepository: WordRepository,
    private val appContext: Context
) : ViewModel() {

    private val step = MutableStateFlow(0)
    private val selectedBookId = MutableStateFlow<Long?>(null)
    private val dailyNewLimit = MutableStateFlow(10)
    private val reminderMinutes = MutableStateFlow<Int?>(null)
    private val saving = MutableStateFlow(false)

    val uiState: StateFlow<OnboardingUiState> = combine(
        combine(step, selectedBookId, dailyNewLimit) { s, b, d -> Triple(s, b, d) },
        combine(reminderMinutes, saving) { r, sv -> r to sv },
        wordRepository.observeBooks()
    ) { (s, b, d), (r, sv), books ->
        OnboardingUiState(
            step = s,
            books = books,
            // 默认选第一本书，用户不动也能一路下一步
            selectedBookId = b ?: books.firstOrNull()?.id,
            dailyNewLimit = d,
            reminderMinutes = r,
            saving = sv
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState())

    fun selectBook(bookId: Long) {
        selectedBookId.value = bookId
    }

    fun setDailyNew(limit: Int) {
        dailyNewLimit.value = limit
    }

    fun setReminder(minutes: Int?) {
        reminderMinutes.value = minutes
        ReminderScheduler.schedule(appContext, minutes)
    }

    fun next() {
        step.value = (step.value + 1).coerceAtMost(3)
    }

    fun back() {
        step.value = (step.value - 1).coerceAtLeast(0)
    }

    /** 收尾：把词书 / 每日数量落进设置，标记引导完成。 */
    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            saving.value = true
            // 用界面上的最终选择（含默认选中第一本），而不是裸的原始流
            settingsRepository.setActiveBook(uiState.value.selectedBookId)
            settingsRepository.setDailyNewLimit(dailyNewLimit.value)
            settingsRepository.completeOnboarding()
            saving.value = false
            onDone()
        }
    }
}
