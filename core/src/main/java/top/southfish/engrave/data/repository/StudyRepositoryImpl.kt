package top.southfish.engrave.data.repository

import androidx.room.withTransaction
import top.southfish.engrave.data.local.AppDatabase
import top.southfish.engrave.data.local.DayIndex
import top.southfish.engrave.data.local.dao.ReviewLogDao
import top.southfish.engrave.data.local.dao.ReviewStateDao
import top.southfish.engrave.data.local.entity.ReviewLogEntity
import top.southfish.engrave.data.mapper.toDomain
import top.southfish.engrave.data.mapper.toEntity
import top.southfish.engrave.domain.model.Grade
import top.southfish.engrave.domain.model.LearningStage
import top.southfish.engrave.domain.model.ReviewState
import top.southfish.engrave.domain.model.StudyItem
import top.southfish.engrave.domain.model.StudyStats
import top.southfish.engrave.domain.repository.StudyRepository
import top.southfish.engrave.domain.srs.ReviewOutcome
import top.southfish.engrave.domain.srs.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

private const val MILLIS_PER_DAY = 86_400_000L

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
    override fun observeStats(bookIds: Set<Long>): Flow<StudyStats> {
        val now = timeProvider.now()
        val today = DayIndex.of(now)
        val allBooks = bookIds.isEmpty()
        val idList = bookIds.toQueryList()
        return combine(
            reviewStateDao.observeStageCounts(allBooks, idList, now),
            reviewLogDao.observeTodaySummary(today, allBooks, idList),
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

    override suspend fun getDueItems(bookIds: Set<Long>, limit: Int, now: Long): List<StudyItem> =
        if (limit <= 0) emptyList()
        else reviewStateDao.getDueItems(bookIds.isEmpty(), bookIds.toQueryList(), now, limit)
            .map { it.toDomain() }

    override suspend fun getNewItems(
        bookIds: Set<Long>,
        excludeIds: List<Long>,
        limit: Int
    ): List<StudyItem> =
        if (limit <= 0) emptyList()
        else reviewStateDao.getNewItems(bookIds.isEmpty(), bookIds.toQueryList(), excludeIds, limit)
            .map { it.toDomain() }

    override suspend fun getItemsByIds(wordIds: List<Long>): List<StudyItem> =
        if (wordIds.isEmpty()) emptyList()
        else reviewStateDao.getItemsByIds(wordIds).map { it.toDomain() }

    override suspend fun countNewLearnedToday(bookIds: Set<Long>): Int =
        reviewLogDao.countNewLearnedOnDay(
            DayIndex.of(timeProvider.now()), bookIds.isEmpty(), bookIds.toQueryList()
        )

    override suspend fun countReviewsToday(bookIds: Set<Long>): Int =
        reviewLogDao.countReviewsOnDay(
            DayIndex.of(timeProvider.now()), bookIds.isEmpty(), bookIds.toQueryList()
        )

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

    override suspend fun rescheduleToTomorrow(wordId: Long) {
        val state = reviewStateDao.getByWordId(wordId) ?: return
        if (state.stage == LearningStage.NEW.name) return
        // 推迟到下一个学习日（凌晨 4 点界），和调度器的天级锚点保持一致
        reviewStateDao.upsert(state.copy(dueAt = DayIndex.nextRollover(timeProvider.now())))
    }

    override suspend fun rescheduleToTomorrowByTerm(term: String) =
        database.wordDao().findByTerm(term)?.let { rescheduleToTomorrow(it.id) } ?: Unit

    override suspend fun graduateStaleCards(now: Long): Int {
        // 当前学习日的 4 点起点：早于它的学习卡 = 隔了一夜，直接毕业
        val cutoff = DayIndex.rolloverStart(now)
        val a = reviewStateDao.graduateStaleLearningCards(cutoff)
        val b = reviewStateDao.graduateStaleRelearningCards(cutoff)
        return a + b
    }

    override suspend fun restoreStateAndDropLastLog(
        wordId: Long,
        restored: ReviewState
    ): Unit = database.withTransaction {
        reviewStateDao.upsert(restored.toEntity())
        reviewLogDao.deleteLatestForWord(wordId)
    }

    /**
     * 空集合 = 全部词书：SQL 里用 :allBooks 短路；IN 列表仍传一个不可能命中
     * 的 id，避免部分 Room 版本对空列表展开成非法 SQL。
     */
    private fun Set<Long>.toQueryList(): List<Long> = if (isEmpty()) listOf(-1L) else toList()
}
