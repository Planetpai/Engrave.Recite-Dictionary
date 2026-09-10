package top.southfish.engrave.domain.repository

import top.southfish.engrave.domain.model.DefinitionDisplay
import top.southfish.engrave.domain.model.Grade
import top.southfish.engrave.domain.model.ReviewState
import top.southfish.engrave.domain.model.StudyItem
import top.southfish.engrave.domain.model.StudyQueueConfig
import top.southfish.engrave.domain.model.StudyStats
import top.southfish.engrave.domain.model.UserProfile
import top.southfish.engrave.domain.srs.ReviewOutcome
import kotlinx.coroutines.flow.Flow

/**
 * 学习过程的数据访问。
 *
 * 有意不包含「一次会话该取哪些卡片」的策略——那属于 [StudyQueueConfig] 和
 * GetStudySessionUseCase 的职责，这里只提供原料，方便单元测试调度策略。
 */
interface StudyRepository {
    /**
     * 首页/统计数据。[bookIds] 为启用的词书 id 集合（可多选），
     * 空集合表示全部词书。
     */
    fun observeStats(bookIds: Set<Long>): Flow<StudyStats>

    /** 到期需要复习的卡片，按逾期程度倒序（拖得越久越靠前）。 */
    suspend fun getDueItems(bookIds: Set<Long>, limit: Int, now: Long): List<StudyItem>

    /**
     * 还没学过的新卡片，按词书内的原始顺序。
     * [excludeIds] 为本次会话已见过、需要跳过的词（浏览续批用）。
     */
    suspend fun getNewItems(bookIds: Set<Long>, excludeIds: List<Long>, limit: Int): List<StudyItem>

    /** 按词 id 批量取学习项（含最新调度状态），恢复中断的会话用。 */
    suspend fun getItemsByIds(wordIds: List<Long>): List<StudyItem>

    /** 今天已经学了多少个新词，用于落实每日新词上限。 */
    suspend fun countNewLearnedToday(bookIds: Set<Long>): Int

    /** 今天已经复习了多少次，用于落实每日复习上限。 */
    suspend fun countReviewsToday(bookIds: Set<Long>): Int


    /** 把词的下次复习推到明天（随刷随学里「还不熟」的词用）。 */
    suspend fun rescheduleToTomorrow(wordId: Long)

    /** 同上，但按词头（错题本里的词可能不在任何词书里）。 */
    suspend fun rescheduleToTomorrowByTerm(term: String)

    /** 落库：更新调度状态 + 追加一条复习日志。必须在同一事务里。 */
    suspend fun persistReview(
        item: StudyItem,
        grade: Grade,
        outcome: ReviewOutcome,
        elapsedMillis: Long
    )

    /**
     * 会话内撤销：把调度状态恢复成 [restored]，并删掉该词最新的一条复习日志。
     * 同一事务，保证状态和日志不会只回滚一半。
     */
    suspend fun restoreStateAndDropLastLog(wordId: Long, restored: ReviewState)
}

/** 用户可调的学习设置，存在 DataStore 里。 */
interface SettingsRepository {
    val queueConfig: Flow<StudyQueueConfig>

    /** 用户资料：头像、昵称。avatarVersion 在替换头像图片后自增，触发界面刷新。 */
    val profile: Flow<UserProfile>

    /** 自定义头像图片的版本号，0 表示还在用预置表情头像。 */
    val avatarImageVersion: Flow<Int>

    /** 首启引导是否已经走完，决定启动时先进引导还是主页。 */
    val onboardingDone: Flow<Boolean>

    /** 详情页释义显示偏好：只中文 / 只英文 / 中英都要。 */
    val definitionDisplay: Flow<DefinitionDisplay>

    /** 每日提醒时间（当天 0 点起的分钟数），null 表示关闭。 */
    val reminderMinutes: Flow<Int?>

    /**
     * 上次退出时的导航栈（首项固定 HOME，路由为实际值如 detail/apple），
     * 应用被杀后重进时按它恢复页面。null 表示从未记录过。
     */
    val navStack: Flow<List<String>?>

    suspend fun setDailyNewLimit(limit: Int)

    /** 设置启用的词书集合（多选）；空集合 = 全部词书。 */
    suspend fun setActiveBooks(bookIds: Set<Long>)

    suspend fun setUserName(name: String)

    suspend fun setAvatarIndex(index: Int)

    /** 记住已设置自定义头像（版本自增）。 */
    suspend fun bumpAvatarImageVersion()

    /** 清空全部设置键，恢复初始状态（重置数据用）。 */
    suspend fun resetAll()

    suspend fun completeOnboarding()

    suspend fun setDefinitionDisplay(display: DefinitionDisplay)

    /** null 表示关闭提醒；调用方负责同步系统闹钟。 */
    suspend fun setReminderMinutes(minutesOfDay: Int?)

    /** 记录 / 清除导航栈（null 或空列表表示清除）。 */
    suspend fun setNavStack(routes: List<String>?)
}
