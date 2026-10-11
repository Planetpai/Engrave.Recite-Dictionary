package top.southfish.engrave.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import top.southfish.engrave.domain.model.DefinitionDisplay
import top.southfish.engrave.domain.model.StudyQueueConfig
import top.southfish.engrave.domain.model.UserProfile
import top.southfish.engrave.domain.repository.SettingsRepository
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
        /** 旧版本：单选词书；保留仅用于读取时一次性迁移，新写入用 ACTIVE_BOOK_IDS。 */
        val ACTIVE_BOOK_ID = longPreferencesKey("active_book_id")
        /** 多选启用的词书 id，逗号分隔；空串 = 全部词书。 */
        val ACTIVE_BOOK_IDS = stringPreferencesKey("active_book_ids")
        val USER_NAME = stringPreferencesKey("user_name")
        val AVATAR_INDEX = intPreferencesKey("avatar_index")
        val AVATAR_IMAGE_VERSION = intPreferencesKey("avatar_image_version")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val DEFINITION_DISPLAY = stringPreferencesKey("definition_display")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val REMINDER_MINUTES = REMINDER_MINUTES_KEY
        val NAV_STACK = stringPreferencesKey("nav_stack")
    }

    override val queueConfig: Flow<StudyQueueConfig> = dataStore.data.map { prefs: Preferences ->
        val defaults = StudyQueueConfig.DEFAULT
        StudyQueueConfig(
            dailyNewLimit = prefs[Keys.DAILY_NEW_LIMIT] ?: defaults.dailyNewLimit,
            // 多选新键优先；老版本（单选 active_book_id）读不到新键时一次性迁移：
            // 旧值 >0 就是那本，null/0 表示全部词书
            activeBookIds = prefs[Keys.ACTIVE_BOOK_IDS]?.let { raw ->
                raw.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
            } ?: prefs[Keys.ACTIVE_BOOK_ID]?.let { old ->
                if (old > 0L) setOf(old) else emptySet()
            } ?: emptySet()
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

    /** 单词发音默认关闭：用户在「其他设置」里明确开启后才自动朗读。 */
    override val ttsEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.TTS_ENABLED] ?: false }

    override val reminderMinutes: Flow<Int?> = dataStore.data.map { it[Keys.REMINDER_MINUTES] }

    /**
     * 路由串里不会有分隔符：detail 的词头经 Uri.encode 编码，其余全是
     * 固定常量，所以用 "|" 拼接 / 拆分是安全的。
     */
    override val navStack: Flow<List<String>?> = dataStore.data.map { prefs ->
        prefs[Keys.NAV_STACK]?.split(SEPARATOR)?.filter { it.isNotBlank() }
    }

    override suspend fun setDailyNewLimit(limit: Int) {
        dataStore.edit { it[Keys.DAILY_NEW_LIMIT] = limit.coerceAtLeast(0) }
    }

    override suspend fun setActiveBooks(bookIds: Set<Long>) {
        dataStore.edit { prefs ->
            // 空集合 = 全部词书：存空串（区别于「键不存在」）
            prefs[Keys.ACTIVE_BOOK_IDS] = bookIds.joinToString(",")
            // 顺手清掉旧版单选键，完成迁移
            prefs.remove(Keys.ACTIVE_BOOK_ID)
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
            prefs.remove(Keys.ACTIVE_BOOK_IDS)
            prefs.remove(Keys.USER_NAME)
            prefs.remove(Keys.AVATAR_INDEX)
            prefs.remove(Keys.AVATAR_IMAGE_VERSION)
            prefs.remove(Keys.ONBOARDING_DONE)
            prefs.remove(Keys.DEFINITION_DISPLAY)
            prefs.remove(Keys.TTS_ENABLED)
            prefs.remove(Keys.REMINDER_MINUTES)
            prefs.remove(Keys.NAV_STACK)
        }
    }

    override suspend fun completeOnboarding() {
        dataStore.edit { it[Keys.ONBOARDING_DONE] = true }
    }

    override suspend fun setDefinitionDisplay(display: DefinitionDisplay) {
        dataStore.edit { it[Keys.DEFINITION_DISPLAY] = display.name }
    }

    override suspend fun setTtsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.TTS_ENABLED] = enabled }
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

    override suspend fun setNavStack(routes: List<String>?) {
        dataStore.edit { prefs ->
            if (routes.isNullOrEmpty()) {
                prefs.remove(Keys.NAV_STACK)
            } else {
                prefs[Keys.NAV_STACK] = routes.joinToString(SEPARATOR)
            }
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 20
        private const val SEPARATOR = "|"
    }
}
