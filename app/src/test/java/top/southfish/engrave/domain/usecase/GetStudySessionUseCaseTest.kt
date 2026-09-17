package top.southfish.engrave.domain.usecase

import top.southfish.engrave.domain.model.Grade
import top.southfish.engrave.domain.model.LearningStage
import top.southfish.engrave.domain.model.ReviewState
import top.southfish.engrave.domain.model.StudyItem
import top.southfish.engrave.domain.model.StudyQueueConfig
import top.southfish.engrave.domain.model.StudyStats
import top.southfish.engrave.domain.model.Word
import top.southfish.engrave.domain.repository.StudyRepository
import top.southfish.engrave.domain.srs.ReviewOutcome
import top.southfish.engrave.domain.srs.TimeProvider
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

        override fun observeStats(bookIds: Set<Long>): Flow<StudyStats> = flowOf(StudyStats())

        override suspend fun getDueItems(
            bookIds: Set<Long>,
            limit: Int,
            now: Long
        ): List<StudyItem> {
            lastDueLimit = limit
            return due.take(limit)
        }

        override suspend fun getNewItems(
            bookIds: Set<Long>,
            excludeIds: List<Long>,
            limit: Int
        ): List<StudyItem> {
            lastNewLimit = limit
            return availableNew.filter { it.wordId !in excludeIds }.take(limit)
        }

        override suspend fun getItemsByIds(wordIds: List<Long>): List<StudyItem> =
            (due + availableNew).filter { it.wordId in wordIds }


        override suspend fun rescheduleToTomorrow(wordId: Long) = Unit

        override suspend fun rescheduleToTomorrowByTerm(term: String) = Unit

        var staleCutoff: Long? = null
        override suspend fun graduateStaleCards(now: Long): Int {
            staleCutoff = now
            return 0
        }

        override suspend fun countNewLearnedToday(bookIds: Set<Long>): Int = newLearnedToday

        override suspend fun countReviewsToday(bookIds: Set<Long>): Int = reviewsToday

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

        assertEquals("批次含缓冲，比额度大", 21, plan.newItems.size)
        assertEquals("浏览目标 = 每日额度", 5, plan.selectionTarget)
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

        assertEquals(21, plan.newItems.size)
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
    fun `开学习会话时先让跨日逾期学习卡毕业`() = runTest {
        val repo = FakeStudyRepository()
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        useCase(config)

        // graduateStaleCards 必须在取队列前被调用，参数是当前时间
        assertEquals(timeProvider.now(), repo.staleCutoff)
    }

    @Test
    fun `续拉新词 - 排除已见词并按缺口加缓冲取词`() = runTest {
        val repo = FakeStudyRepository(availableNew = newItems(1L..50L))
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        val more = useCase.fetchMoreNewItems(
            config = config,
            excludeIds = (1L..40L).toList(),
            need = 5
        )

        // limit = 缺口 5 + 缓冲 30 = 35；前 40 个已见被排除，只剩 41..50
        assertEquals(35, repo.lastNewLimit)
        assertEquals((41L..50L).toList(), more.map { it.wordId })
    }

    @Test
    fun `续拉新词 - 缺口为零不取词`() = runTest {
        val repo = FakeStudyRepository(availableNew = newItems(1L..10L))
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        assertTrue(useCase.fetchMoreNewItems(config, emptyList(), need = 0).isEmpty())
        assertEquals("不该查数据库", -1, repo.lastNewLimit)
    }

    @Test
    fun `activeBookIds 会透传给仓库`() = runTest {
        val delegate = FakeStudyRepository(
            due = listOf(item(1)),
            availableNew = listOf(item(100, LearningStage.NEW))
        )
        var receivedBookIds: Set<Long> = emptySet()
        // by 委托的成员不是父类成员，所以不能用 super 调用，得直接走 delegate
        val repo = object : StudyRepository by delegate {
            override suspend fun getDueItems(
                bookIds: Set<Long>,
                limit: Int,
                now: Long
            ): List<StudyItem> {
                receivedBookIds = bookIds
                return delegate.getDueItems(bookIds, limit, now)
            }
        }
        val useCase = GetStudySessionUseCase(repo, timeProvider)

        useCase(config.copy(activeBookIds = setOf(7L, 9L)))

        assertEquals(setOf(7L, 9L), receivedBookIds)
    }
}
