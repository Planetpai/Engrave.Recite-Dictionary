package com.example.vocabwatch.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.vocabwatch.domain.model.StudyQueueConfig
import com.example.vocabwatch.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "vocab_settings")

/**
 * 学习设置的持久化。
 *
 * 只存用户明确改过的键，没存过的一律回落到 [StudyQueueConfig] 的默认值，
 * 这样以后调整默认参数不需要写数据迁移。
 */
class SettingsRepositoryImpl(context: Context) : SettingsRepository {

    private val dataStore = context.applicationContext.settingsDataStore

    private object Keys {
        val DAILY_NEW_LIMIT = intPreferencesKey("daily_new_limit")
        val DAILY_REVIEW_LIMIT = intPreferencesKey("daily_review_limit")
        val ACTIVE_BOOK_ID = longPreferencesKey("active_book_id")
    }

    override val queueConfig: Flow<StudyQueueConfig> = dataStore.data.map { prefs: Preferences ->
        val defaults = StudyQueueConfig.DEFAULT
        StudyQueueConfig(
            dailyNewLimit = prefs[Keys.DAILY_NEW_LIMIT] ?: defaults.dailyNewLimit,
            dailyReviewLimit = prefs[Keys.DAILY_REVIEW_LIMIT] ?: defaults.dailyReviewLimit,
            activeBookId = prefs[Keys.ACTIVE_BOOK_ID]
        )
    }

    override suspend fun setDailyNewLimit(limit: Int) {
        dataStore.edit { it[Keys.DAILY_NEW_LIMIT] = limit.coerceAtLeast(0) }
    }

    override suspend fun setDailyReviewLimit(limit: Int) {
        dataStore.edit { it[Keys.DAILY_REVIEW_LIMIT] = limit.coerceAtLeast(0) }
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
}
