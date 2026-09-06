package com.example.vocabwatch.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.vocabwatch.domain.model.DefinitionDisplay
import com.example.vocabwatch.domain.model.StudyQueueConfig
import com.example.vocabwatch.domain.model.UserProfile
import com.example.vocabwatch.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore by preferencesDataStore(name = "vocab_settings")

/** 提醒时间单独暴露：BootReceiver 重排闹钟时要直接读它。 */
val REMINDER_MINUTES_KEY = intPreferencesKey("reminder_minutes")

/**
 * 学习设置和用户资料的持久化。
 *
 * 只存用户明确改过的键，没存过的一律回落到默认值，这样以后调整默认参数
 * 不需要写数据迁移。
 */
class SettingsRepositoryImpl(context: Context) : SettingsRepository {

    private val dataStore = context.applicationContext.settingsDataStore

    private object Keys {
        val DAILY_NEW_LIMIT = intPreferencesKey("daily_new_limit")
        val ACTIVE_BOOK_ID = longPreferencesKey("active_book_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val AVATAR_INDEX = intPreferencesKey("avatar_index")
        val AVATAR_IMAGE_VERSION = intPreferencesKey("avatar_image_version")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val DEFINITION_DISPLAY = stringPreferencesKey("definition_display")
        val REMINDER_MINUTES = REMINDER_MINUTES_KEY
    }

    override val queueConfig: Flow<StudyQueueConfig> = dataStore.data.map { prefs: Preferences ->
        val defaults = StudyQueueConfig.DEFAULT
        StudyQueueConfig(
            dailyNewLimit = prefs[Keys.DAILY_NEW_LIMIT] ?: defaults.dailyNewLimit,
            activeBookId = prefs[Keys.ACTIVE_BOOK_ID]
        )
    }

    override val profile: Flow<UserProfile> = dataStore.data.map { prefs ->
        UserProfile(
            userName = prefs[Keys.USER_NAME] ?: "",
            avatarIndex = (prefs[Keys.AVATAR_INDEX] ?: 0).coerceIn(0, UserProfile.AVATARS.lastIndex)
        )
    }

    override val avatarImageVersion: Flow<Int> =
        dataStore.data.map { it[Keys.AVATAR_IMAGE_VERSION] ?: 0 }

    override val onboardingDone: Flow<Boolean> =
        dataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }

    override val definitionDisplay: Flow<DefinitionDisplay> = dataStore.data.map { prefs ->
        val raw = prefs[Keys.DEFINITION_DISPLAY]
        DefinitionDisplay.entries.firstOrNull { it.name == raw } ?: DefinitionDisplay.BOTH
    }

    override val reminderMinutes: Flow<Int?> = dataStore.data.map { it[Keys.REMINDER_MINUTES] }

    override suspend fun setDailyNewLimit(limit: Int) {
        dataStore.edit { it[Keys.DAILY_NEW_LIMIT] = limit.coerceAtLeast(0) }
    }

    override suspend fun setActiveBook(bookId: Long?) {
        dataStore.edit { prefs ->
            if (bookId == null) {
                prefs.remove(Keys.ACTIVE_BOOK_ID)
            } else {
                prefs[Keys.ACTIVE_BOOK_ID] = bookId
            }
        }
    }

    override suspend fun setUserName(name: String) {
        dataStore.edit { it[Keys.USER_NAME] = name.trim().take(MAX_NAME_LENGTH) }
    }

    override suspend fun setAvatarIndex(index: Int) {
        dataStore.edit {
            it[Keys.AVATAR_INDEX] = index.coerceIn(0, UserProfile.AVATARS.lastIndex)
        }
    }

    override suspend fun bumpAvatarImageVersion() {
        dataStore.edit { prefs ->
            prefs[Keys.AVATAR_IMAGE_VERSION] = (prefs[Keys.AVATAR_IMAGE_VERSION] ?: 0) + 1
        }
    }

    override suspend fun resetAll() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.DAILY_NEW_LIMIT)
            prefs.remove(Keys.ACTIVE_BOOK_ID)
            prefs.remove(Keys.USER_NAME)
            prefs.remove(Keys.AVATAR_INDEX)
            prefs.remove(Keys.AVATAR_IMAGE_VERSION)
            prefs.remove(Keys.ONBOARDING_DONE)
            prefs.remove(Keys.DEFINITION_DISPLAY)
            prefs.remove(Keys.REMINDER_MINUTES)
        }
    }

    override suspend fun completeOnboarding() {
        dataStore.edit { it[Keys.ONBOARDING_DONE] = true }
    }

    override suspend fun setDefinitionDisplay(display: DefinitionDisplay) {
        dataStore.edit { it[Keys.DEFINITION_DISPLAY] = display.name }
    }

    override suspend fun setReminderMinutes(minutesOfDay: Int?) {
        dataStore.edit { prefs ->
            if (minutesOfDay == null) {
                prefs.remove(Keys.REMINDER_MINUTES)
            } else {
                prefs[Keys.REMINDER_MINUTES] = minutesOfDay.coerceIn(0, 24 * 60 - 1)
            }
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 20
    }
}
