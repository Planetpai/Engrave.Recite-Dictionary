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
 * 队列组装策略的测试。
 *
 * 用假的 StudyRepository 提供原料，验证的是策略本身：
 * 每日上限怎么卡、会话长度怎么截断、新词按什么比例插进去。
 */
class GetStudySessionUseCaseTest {

    private val now = 1_700_000_000_000L
    private val timeProvider = TimeProvider { now }

    private fun item(id: Long, stage: LearningStage = LearningStage.REVIEW) = StudyItem(
        word = Word(id = id, bookId = 1L, term = "w$id", meaning = "m$id"),
        state = ReviewState(wordId = id, stage = stage)
    )

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

        override suspend fun countNewLearnedToday(bookId: Long?): Int = newLearnedToday
        override suspend fun countReviewsToday(bookId: Long?): Int = reviewsToday

        override suspend fun persistReview(
            item: StudyItem,
            grade: Grade,
            outcome: ReviewOutcome,
            elapsedMillis: Long
        ) = Unit
    }

    private val config = StudyQueueConfig(
        dailyNewLimit = 5,
        dailyReviewLimit = 100,
        sessionSize = 10,
        newCardShare = 0.4
    )

    @Test
    fun `到期卡不多时 - 剩余位置用来放新词`() = runTest {
        val repo = FakeStudyRepository(
            due = (1L..3L).map { item(it) },
            availableNew = (100L..120L).map { item(it, LearningStage.NEW) }
        )
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        val session = useCase(config)

        assertEquals(3 + 4, session.size)
        assertEquals(3, session.count { !it.isNew })
        assertEquals(4, session.count { it.isNew })
    }

    @Test
    fun `新词数量受每日新词上限约束`() = runTest {
        val repo = FakeStudyRepository(
            due = emptyList(),
            availableNew = (100L..150L).map { item(it, LearningStage.NEW) }
        )
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        val session = useCase(config)

        // sessionSize 10 × newCardShare 0.4 = 4，再被 dailyNewLimit 5 卡住，取小者
        assertEquals(4, session.size)
        assertEquals(4, repo.lastNewLimit)
    }

    @Test
    fun `今天新词额度用完 - 一张新词都不再出`() = runTest {
        val repo = FakeStudyRepository(
            due = (1L..2L).map { item(it) },
            availableNew = (100L..120L).map { item(it, LearningStage.NEW) },
            newLearnedToday = 5
        )
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        val session = useCase(config)

        assertEquals(2, session.size)
        assertTrue(session.none { it.isNew })
    }

    @Test
    fun `今天复习额度用完 - 只出新词`() = runTest {
        val repo = FakeStudyRepository(
            due = (1L..8L).map { item(it) },
            availableNew = (100L..120L).map { item(it, LearningStage.NEW) },
            reviewsToday = 100
        )
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        val session = useCase(config)

        assertTrue(session.all { it.isNew })
        assertEquals(-1, repo.lastDueLimit) // 额度为 0 时压根不该去查数据库
    }

    @Test
    fun `会话长度不会超过 sessionSize`() = runTest {
        val repo = FakeStudyRepository(
            due = (1L..50L).map { item(it) },
            availableNew = (100L..150L).map { item(it, LearningStage.NEW) }
        )
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        val session = useCase(config)

        assertEquals(10, session.size)
    }

    @Test
    fun `到期卡已经占满会话 - 不再塞新词`() = runTest {
        val full = StudyQueueConfig(
            dailyNewLimit = 5,
            dailyReviewLimit = 100,
            sessionSize = 4,
            newCardShare = 0.4
        )
        val repo = FakeStudyRepository(
            due = (1L..10L).map { item(it) },
            availableNew = (100L..120L).map { item(it, LearningStage.NEW) }
        )
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        val session = useCase(full)

        assertEquals(4, session.size)
        assertTrue(session.none { it.isNew })
    }

    @Test
    fun `新旧词交替排列 而不是把新词全堆在末尾`() = runTest {
        val repo = FakeStudyRepository(
            due = (1L..6L).map { item(it) },
            availableNew = (100L..105L).map { item(it, LearningStage.NEW) }
        )
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        val session = useCase(config)

        val lastNewIndex = session.indexOfLast { it.isNew }
        assertTrue(
            "新词应当出现在会话中间而不是全堆在末尾，实际最后一张新词在下标 $lastNewIndex / ${session.size}",
            lastNewIndex < session.size - 1
        )
    }

    @Test
    fun `没有任何可学内容 - 返回空队列`() = runTest {
        val repo = FakeStudyRepository()
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        assertTrue(useCase(config).isEmpty())
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
