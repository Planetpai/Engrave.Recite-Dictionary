package com.example.vocabwatch.domain.repository

import com.example.vocabwatch.domain.model.Grade
import com.example.vocabwatch.domain.model.StudyItem
import com.example.vocabwatch.domain.model.StudyQueueConfig
import com.example.vocabwatch.domain.model.StudyStats
import com.example.vocabwatch.domain.srs.ReviewOutcome
import kotlinx.coroutines.flow.Flow

/**
 * 学习过程的数据访问。
 *
 * 有意不包含「一次会话该取哪些卡片」的策略——那属于 [StudyQueueConfig] 和
 * GetStudySessionUseCase 的职责，这里只提供原料，方便单元测试调度策略。
 */
interface StudyRepository {
    fun observeStats(bookId: Long?): Flow<StudyStats>

    /** 到期需要复习的卡片，按逾期程度倒序（拖得越久越靠前）。 */
    suspend fun getDueItems(bookId: Long?, limit: Int, now: Long): List<StudyItem>

    /** 还没学过的新卡片，按词书内的原始顺序。 */
    suspend fun getNewItems(bookId: Long?, limit: Int): List<StudyItem>

    /** 今天已经学了多少个新词，用于落实每日新词上限。 */
    suspend fun countNewLearnedToday(bookId: Long?): Int

    /** 今天已经复习了多少次，用于落实每日复习上限。 */
    suspend fun countReviewsToday(bookId: Long?): Int

    /** 落库：更新调度状态 + 追加一条复习日志。必须在同一事务里。 */
    suspend fun persistReview(
        item: StudyItem,
        grade: Grade,
        outcome: ReviewOutcome,
        elapsedMillis: Long
    )
}

/** 用户可调的学习设置，存在 DataStore 里。 */
interface SettingsRepository {
    val queueConfig: Flow<StudyQueueConfig>

    suspend fun setDailyNewLimit(limit: Int)

    suspend fun setDailyReviewLimit(limit: Int)

    suspend fun setActiveBook(bookId: Long?)
}
