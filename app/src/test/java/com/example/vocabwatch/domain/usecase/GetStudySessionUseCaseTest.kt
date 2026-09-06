package com.example.vocabwatch.domain.usecase

import com.example.vocabwatch.domain.model.Grade
import com.example.vocabwatch.domain.model.LearningStage
import com.example.vocabwatch.domain.model.ReviewState
import com.example.vocabwatch.domain.model.StudyItem
import com.example.vocabwatch.domain.model.StudyQueueConfig
import com.example.vocabwatch.domain.model.StudyStats
import com.example.vocabwatch.domain.model.Word
import com.example.vocabwatch.domain.repository.StudyRepository
import com.example.vocabwatch.domain.srs.ReviewOutcome
import com.example.vocabwatch.domain.srs.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话计划的测试。
 *
 * 用假的 StudyRepository 提供原料，验证策略本身：每日额度怎么卡、
 * 新词和到期词各取多少、额度用完时是否还去查库。
 */
class GetStudySessionUseCaseTest {

    private val now = 1_700_000_000_000L
    private val timeProvider = TimeProvider { now }

    private fun item(id: Long, stage: LearningStage = LearningStage.REVIEW) = StudyItem(
        word = Word(id = id, bookId = 1L, term = "w$id", meaning = "m$id"),
        state = ReviewState(wordId = id, stage = stage)
    )

    private fun newItems(ids: LongRange) = ids.map { item(it, LearningStage.NEW) }

    /** 记录被请求过的 limit，用来断言用例没有多要数据 */
    private class FakeStudyRepository(
        val due: List<StudyItem> = emptyList(),
        val availableNew: List<StudyItem> = emptyList(),
        val newLearnedToday: Int = 0,
        val reviewsToday: Int = 0
    ) : StudyRepository {
        var lastDueLimit: Int = -1
        var lastNewLimit: Int = -1

        override fun observeStats(bookId: Long?): Flow<StudyStats> = flowOf(StudyStats())

        override suspend fun getDueItems(bookId: Long?, limit: Int, now: Long): List<StudyItem> {
            lastDueLimit = limit
            return due.take(limit)
        }

        override suspend fun getNewItems(bookId: Long?, limit: Int): List<StudyItem> {
            lastNewLimit = limit
            return availableNew.take(limit)
        }

        override suspend fun getItemsByIds(wordIds: List<Long>): List<StudyItem> =
            (due + availableNew).filter { it.wordId in wordIds }

        override suspend fun getAgainItemsToday(bookId: Long?, limit: Int): List<StudyItem> = emptyList()

        override suspend fun rescheduleToTomorrow(wordId: Long) = Unit

        override suspend fun rescheduleToTomorrowByTerm(term: String) = Unit

        override suspend fun countNewLearnedToday(bookId: Long?): Int = newLearnedToday

        override suspend fun countReviewsToday(bookId: Long?): Int = reviewsToday

        override suspend fun persistReview(
            item: StudyItem,
            grade: Grade,
            outcome: ReviewOutcome,
            elapsedMillis: Long
        ) = Unit

        override suspend fun restoreStateAndDropLastLog(wordId: Long, restored: ReviewState) = Unit
    }

    private val config = StudyQueueConfig(dailyNewLimit = 5)

    @Test
    fun `新词和到期词分别按各自额度取出`() = runTest {
        val repo = FakeStudyRepository(
            due = (1L..3L).map { item(it) },
            availableNew = newItems(100L..120L)
        )
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        val plan = useCase(config)

        assertEquals(5, plan.newItems.size)
        assertEquals(3, plan.dueItems.size)
    }

    @Test
    fun `今天新词额度用完 - 不取新词也不查数据库`() = runTest {
        val repo = FakeStudyRepository(
            due = (1L..2L).map { item(it) },
            availableNew = newItems(100L..120L),
            newLearnedToday = 5
        )
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        val plan = useCase(config)

        assertTrue(plan.newItems.isEmpty())
        assertEquals(2, plan.dueItems.size)
        assertEquals(-1, repo.lastNewLimit)
    }

    @Test
    fun `复习不设上限 - 到期词全部取出`() = runTest {
        val repo = FakeStudyRepository(
            due = (1L..80L).map { item(it) },
            availableNew = newItems(100L..120L)
        )
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        val plan = useCase(config)

        assertEquals(5, plan.newItems.size)
        assertEquals("到期多少复习多少，不再有每日复习额度", 80, plan.dueItems.size)
        assertEquals(GetStudySessionUseCase.REVIEW_UNLIMITED, repo.lastDueLimit)
    }

    @Test
    fun `没有任何可学内容 - 计划为空`() = runTest {
        val repo = FakeStudyRepository()
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        assertTrue(useCase(config).isEmpty)
    }

    @Test
    fun `activeBookId 会透传给仓库`() = runTest {
        val delegate = FakeStudyRepository(
            due = listOf(item(1)),
            availableNew = listOf(item(100, LearningStage.NEW))
        )
        var receivedBookId: Long? = -1L
        // by 委托的成员不是父类成员，所以不能用 super 调用，得直接走 delegate
        val repo = object : StudyRepository by delegate {
            override suspend fun getDueItems(
                bookId: Long?,
                limit: Int,
                now: Long
            ): List<StudyItem> {
                receivedBookId = bookId
                return delegate.getDueItems(bookId, limit, now)
            }
        }
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        useCase(config.copy(activeBookId = 7L))

        assertEquals(7L, receivedBookId)
    }
}
