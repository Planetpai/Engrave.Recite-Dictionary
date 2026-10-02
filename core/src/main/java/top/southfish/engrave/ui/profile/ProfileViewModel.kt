package top.southfish.engrave.ui.profile

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.southfish.engrave.domain.repository.SettingsRepository
import top.southfish.engrave.domain.repository.StudyRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileUiState(
    val userName: String = "",
    val avatarIndex: Int = 0,
    val avatarImageVersion: Int = 0,
    val streakDays: Int = 0,
    val learnedWords: Int = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModel(
    private val settingsRepository: SettingsRepository,
    studyRepository: StudyRepository
) : ViewModel() {

    val uiState: StateFlow<ProfileUiState> = settingsRepository.queueConfig
        .flatMapLatest { config ->
            combine(
                settingsRepository.profile,
                settingsRepository.avatarImageVersion,
                studyRepository.observeStats(config.activeBookIds)
            ) { profile, avatarVersion, stats ->
                ProfileUiState(
                    userName = profile.userName,
                    avatarIndex = profile.avatarIndex,
                    avatarImageVersion = avatarVersion,
                    streakDays = stats.streakDays,
                    learnedWords = stats.learnedWords
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    fun setUserName(name: String) {
        viewModelScope.launch { settingsRepository.setUserName(name) }
    }
}

/**
 * 选图页的 VM：把用户在应用内相册浏览器里选中的图片压进 filesDir，
 * 然后自增版本号让我的页刷新头像。落盘细节都在 [AvatarImage]。
 */
class AvatarPickerViewModel(
    private val settingsRepository: SettingsRepository,
    private val appContext: Context
) : ViewModel() {

    fun save(uri: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = AvatarImage.saveFromUri(appContext, uri)
            if (ok) settingsRepository.bumpAvatarImageVersion()
            onDone(ok)
        }
    }
}
