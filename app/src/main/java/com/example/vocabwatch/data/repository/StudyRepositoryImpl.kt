package com.example.vocabwatch.data.repository

import androidx.room.withTransaction
import com.example.vocabwatch.data.local.AppDatabase
import com.example.vocabwatch.data.local.DayIndex
import com.example.vocabwatch.data.local.dao.ReviewLogDao
import com.example.vocabwatch.data.local.dao.ReviewStateDao
import com.example.vocabwatch.data.local.entity.ReviewLogEntity
import com.example.vocabwatch.data.mapper.toDomain
import com.example.vocabwatch.data.mapper.toEntity
import com.example.vocabwatch.domain.model.Grade
import com.example.vocabwatch.domain.model.StudyItem
import com.example.vocabwatch.domain.model.StudyStats
import com.example.vocabwatch.domain.repository.StudyRepository
import com.example.vocabwatch.domain.srs.ReviewOutcome
import com.example.vocabwatch.domain.srs.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class StudyRepositoryImpl(
    private val database: AppDatabase,
    private val reviewStateDao: ReviewStateDao,
    private val reviewLogDao: ReviewLogDao,
    private val timeProvider: TimeProvider
) : StudyRepository {

    /**
     * now 和 today 在收集这个 Flow 时就算好了。Room 的 Flow 只在表变化时重发，
     * 不会因为时间流逝而重新计算到期数——但手表上的统计页停留时间很短，
     * 退出再进就会拿到新的 now，所以够用。真要长时间挂着，
     * 上层加个每分钟重收集即可。
     */
    override fun observeStats(bookId: Long?): Flow<StudyStats> {
        val now = timeProvider.now()
        val today = DayIndex.of(now)
        return combine(
            reviewStateDao.observeStageCounts(bookId, now),
            reviewLogDao.observeTodaySummary(today, bookId),
            reviewLogDao.observeDistinctDays()
        ) { counts, summary, days ->
            StudyStats(
                totalWords = counts.total,
                learnedWords = counts.learned,
                dueNow = counts.dueNow,
                newRemaining = counts.newRemaining,
                reviewedToday = summary.reviewedToday,
                correctTodayRate = if (summary.reviewedToday == 0) {
                    null
                } else {
                    summary.passedToday.toDouble() / summary.reviewedToday
                },
                streakDays = DayIndex.streakFrom(days, today)
            )
        }
    }

    override suspend fun getDueItems(bookId: Long?, limit: Int, now: Long): List<StudyItem> =
        if (limit <= 0) emptyList()
        else reviewStateDao.getDueItems(bookId, now, limit).map { it.toDomain() }

    override suspend fun getNewItems(bookId: Long?, limit: Int): List<StudyItem> =
        if (limit <= 0) emptyList()
        else reviewStateDao.getNewItems(bookId, limit).map { it.toDomain() }

    override suspend fun countNewLearnedToday(bookId: Long?): Int =
        reviewLogDao.countNewLearnedOnDay(DayIndex.of(timeProvider.now()), bookId)

    override suspend fun countReviewsToday(bookId: Long?): Int =
        reviewLogDao.countReviewsOnDay(DayIndex.of(timeProvider.now()), bookId)

    /** 更新调度状态和写日志必须在同一个事务里，否则崩在中途会丢日志或状态不一致。 */
    override suspend fun persistReview(
        item: StudyItem,
        grade: Grade,
        outcome: ReviewOutcome,
        elapsedMillis: Long
    ): Unit = database.withTransaction {
        val reviewedAt = outcome.newState.lastReviewedAt ?: timeProvider.now()

        reviewStateDao.upsert(outcome.newState.toEntity())

        reviewLogDao.insert(
            ReviewLogEntity(
                wordId = item.wordId,
                grade = grade.name,
                reviewedAt = reviewedAt,
                reviewDay = DayIndex.of(reviewedAt),
                stageBefore = item.state.stage.name,
                stageAfter = outcome.newState.stage.name,
                intervalBeforeDays = item.state.intervalDays,
                intervalAfterDays = outcome.newState.intervalDays,
                elapsedMillis = elapsedMillis
            )
        )
    }
}
