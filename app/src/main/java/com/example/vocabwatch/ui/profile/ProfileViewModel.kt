package com.example.vocabwatch.ui.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vocabwatch.domain.model.UserProfile
import com.example.vocabwatch.domain.repository.SettingsRepository
import com.example.vocabwatch.domain.repository.StudyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val appContext: Context,
    studyRepository: StudyRepository
) : ViewModel() {

    val uiState: StateFlow<ProfileUiState> = settingsRepository.queueConfig
        .flatMapLatest { config ->
            combine(
                settingsRepository.profile,
                settingsRepository.avatarImageVersion,
                studyRepository.observeStats(config.activeBookId)
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

    /** 点一下头像换下一个预置表情（没有选图能力时的兜底）。 */
    fun cycleAvatar() {
        val next = (uiState.value.avatarIndex + 1) % UserProfile.AVATARS.size
        viewModelScope.launch { settingsRepository.setAvatarIndex(next) }
    }

    /**
     * 用户从相册选了图：解码、等比压到 256px 内、存 filesDir/avatar.jpg，
     * 然后自增版本号让界面刷新。头像圆框里用 ContentScale.Crop 居中裁剪。
     */
    fun setAvatarFromUri(uri: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val sample = decodeScaled(uri, 512)
                        ?: throw IllegalArgumentException("图片解码失败")
                    appContext.openFileOutput(AVATAR_FILE, Context.MODE_PRIVATE).use { out ->
                        sample.compress(Bitmap.CompressFormat.JPEG, 88, out)
                    }
                }.isSuccess
            }
            if (ok) settingsRepository.bumpAvatarImageVersion()
            onDone(ok)
        }
    }

    /** 先探测尺寸再等比缩放，避免大图直接占满内存。 */
    private fun decodeScaled(uri: Uri, maxSide: Int): Bitmap? {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide / 2 && bounds.outHeight / (sample * 2) >= maxSide / 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        val w = decoded.width
        val h = decoded.height
        val scale = maxSide.toFloat() / maxOf(w, h)
        return if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
        } else {
            decoded
        }
    }

    companion object {
        const val AVATAR_FILE = "avatar.jpg"
    }
}
