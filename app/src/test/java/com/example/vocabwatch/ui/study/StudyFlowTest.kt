package com.example.vocabwatch.ui.study

import com.example.vocabwatch.domain.model.Grade
import com.example.vocabwatch.domain.model.LearningStage
import com.example.vocabwatch.domain.model.ReviewState
import com.example.vocabwatch.domain.model.StudyItem
import com.example.vocabwatch.domain.model.StudyQueueConfig
import com.example.vocabwatch.domain.model.StudyStats
import com.example.vocabwatch.domain.model.Word
import com.example.vocabwatch.domain.repository.SettingsRepository
import com.example.vocabwatch.domain.repository.StudyRepository
import com.example.vocabwatch.domain.srs.ReviewOutcome
import com.example.vocabwatch.domain.srs.Sm2Scheduler
import com.example.vocabwatch.domain.srs.TimeProvider
import com.example.vocabwatch.domain.usecase.GetStudySessionUseCase
import com.example.vocabwatch.domain.usecase.SubmitReviewUseCase
import com.example.vocabwatch.domain.usecase.UndoReviewUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 学习页状态机的测试：浏览选词 → 正式测试 → 复习，以及答错重排和双击撤销。
 *
 * 调度器用真的 Sm2Scheduler，只把仓库换成假的，顺带验证用例层和状态机的
 * 衔接。主线程换成 UnconfinedTestDispatcher 之后，viewModelScope 里的落库
 * 会同步跑完，断言不需要额外等待。
 */
class StudyFlowTest {

    private class FakeStudyRepository(
        val due: List<StudyItem> = emptyList(),
        val availableNew: List<StudyItem> = emptyList()
    ) : StudyRepository {
        /** 落库过的评分：wordId 到 grade */
        val persisted = mutableListOf<Pair<Long, Grade>>()

        /** 撤销时恢复过状态的 wordId */
        val restored = mutableListOf<Long>()

        override fun observeStats(bookId: Long?): Flow<StudyStats> = flowOf(StudyStats())

        override suspend fun getDueItems(bookId: Long?, limit: Int, now: Long) = due.take(limit)

        override suspend fun getNewItems(bookId: Long?, limit: Int) = availableNew.take(limit)

        override suspend fun countNewLearnedToday(bookId: Long?): Int = 0

        override suspend fun countReviewsToday(bookId: Long?): Int = 0

        override suspend fun persistReview(
            item: StudyItem,
            grade: Grade,
            outcome: ReviewOutcome,
            elapsedMillis: Long
        ) {
            persisted.add(item.wordId to grade)
        }

        override suspend fun restoreStateAndDropLastLog(wordId: Long, restoredState: ReviewState) {
            restored.add(wordId)
        }
    }

    private class FakeSettings(config: StudyQueueConfig) : SettingsRepository {
        override val queueConfig: Flow<StudyQueueConfig> = flowOf(config)
        override suspend fun setDailyNewLimit(limit: Int) = Unit
        override suspend fun setDailyReviewLimit(limit: Int) = Unit
        override suspend fun setActiveBook(bookId: Long?) = Unit
    }

    /** 可控时钟：flip() 有入场保护期，测试里要能把时间拨过去 */
    private var clock = 1_700_000_000_000L
    private val timeProvider = TimeProvider { clock }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun item(id: Long, stage: LearningStage = LearningStage.NEW) = StudyItem(
        word = Word(
            id = id,
            bookId = 1L,
            term = "word$id",
            meaning = "意思$id",
            phonetic = "/w$id/",
            example = "example $id",
            exampleTranslation = "例句翻译 $id"
        ),
        state = ReviewState(wordId = id, stage = stage)
    )

    private fun newItems(ids: LongRange) = ids.map { item(it) }

    private fun viewModel(
        repo: FakeStudyRepository,
        dailyNewLimit: Int = 10,
        dailyReviewLimit: Int = 100
    ) = StudyViewModel(
        getStudySession = GetStudySessionUseCase(repo, timeProvider),
        submitReview = SubmitReviewUseCase(repo, Sm2Scheduler(), timeProvider),
        undoReview = UndoReviewUseCase(repo),
        settingsRepository = FakeSettings(
            StudyQueueConfig(dailyNewLimit = dailyNewLimit, dailyReviewLimit = dailyReviewLimit)
        ),
        timeProvider = timeProvider
    )

    private fun StudyViewModel.browsing() = uiState.value as StudyUiState.Browsing

    private fun StudyViewModel.testing() = uiState.value as StudyUiState.Testing

    /** 拨过翻面保护期再翻面 */
    private fun StudyViewModel.flipNow() {
        clock += 1_000
        flip()
    }

    @Test
    fun `浏览阶段右滑已学过 - 按 EASY 落库且不进待背列表`() {
        val repo = FakeStudyRepository(availableNew = newItems(1L..5L))
        val vm = viewModel(repo, dailyNewLimit = 3)

        assertEquals(3, vm.browsing().remaining)

        vm.answer(Verdict.KNOWN)

        assertEquals(listOf(1L to Grade.EASY), repo.persisted)
        assertEquals(2, vm.browsing().remaining)
        assertEquals(0, vm.browsing().selectedCount)
    }

    @Test
    fun `浏览阶段选够每日新词数 - 提示选够了开始背单词`() {
        val repo = FakeStudyRepository(availableNew = newItems(1L..5L))
        val vm = viewModel(repo, dailyNewLimit = 3)

        repeat(2) { vm.answer(Verdict.UNKNOWN) }
        assertEquals(2, vm.browsing().selectedCount)
        assertTrue("浏览阶段的不认识不该写库", repo.persisted.isEmpty())

        vm.answer(Verdict.UNKNOWN)

        val prompt = vm.uiState.value as StudyUiState.Break
        assertEquals("选够了", prompt.title)
        assertEquals("开始背单词", prompt.actionLabel)
        assertTrue(repo.persisted.isEmpty())
    }

    @Test
    fun `上拉露出例句 - 卡片不推进`() {
        val repo = FakeStudyRepository(availableNew = newItems(1L..3L))
        val vm = viewModel(repo)

        vm.openReveal()

        assertTrue(vm.browsing().card.exampleRevealed)
        assertEquals(3, vm.browsing().remaining)
        assertEquals(1L, vm.browsing().card.item.wordId)
    }

    @Test
    fun `开始背单词后 - 三种考法按位置轮换`() {
        val repo = FakeStudyRepository(availableNew = newItems(1L..6L))
        val vm = viewModel(repo, dailyNewLimit = 3)
        repeat(3) { vm.answer(Verdict.UNKNOWN) }

        vm.continueAfterBreak()

        val state = vm.testing()
        assertEquals(TestPhase.FORMAL, state.phase)
        assertEquals(3, state.remaining)
        assertEquals(CardKind.EN_TO_ZH, state.card.kind)
    }

    @Test
    fun `拼写卡 - 乱序字母和正确答案等长同字母但不同序`() {
        val repo = FakeStudyRepository(availableNew = newItems(1L..6L))
        val vm = viewModel(repo, dailyNewLimit = 3)
        repeat(3) { vm.answer(Verdict.UNKNOWN) }
        vm.continueAfterBreak()

        vm.flipNow()
        vm.answer(Verdict.KNOWN)
        vm.flipNow()
        vm.answer(Verdict.KNOWN)

        val spelling = vm.testing().card
        assertEquals(CardKind.SPELLING, spelling.kind)
        // word3 里的数字不算字母，拼写卡只考 word
        assertEquals("word".toList(), spelling.answer)
        assertEquals(spelling.answer.size, spelling.scrambled.size)
        assertEquals(spelling.answer.sorted(), spelling.scrambled.sorted())
        assertTrue("乱序不能正好是答案本身", spelling.scrambled != spelling.answer)
    }

    @Test
    fun `答不会的卡 - 换考法带着新状态隔两张重排`() {
        val repo = FakeStudyRepository(availableNew = newItems(1L..6L))
        val vm = viewModel(repo, dailyNewLimit = 3)
        repeat(3) { vm.answer(Verdict.UNKNOWN) }
        vm.continueAfterBreak()

        val first = vm.testing().card
        vm.flipNow()
        vm.answer(Verdict.UNKNOWN)

        assertEquals(listOf(1L to Grade.AGAIN), repo.persisted)
        // 队列里还有两张，重排插在隔两张的位置，所以紧接着出现的仍是下一个词
        assertEquals(2L, vm.testing().card.item.wordId)
        assertEquals(3, vm.testing().remaining)

        vm.flipNow()
        vm.answer(Verdict.KNOWN)
        vm.answer(Verdict.KNOWN)

        val replay = vm.testing().card
        assertEquals(1L, replay.item.wordId)
        assertTrue("重排要换一种考法", replay.kind != first.kind)
        assertEquals("重排的卡要带着刚算出来的新状态", LearningStage.LEARNING, replay.item.state.stage)
    }

    @Test
    fun `双击撤销 - 原卡回队首且重排的那张被摘掉`() {
        val repo = FakeStudyRepository(availableNew = newItems(1L..4L))
        val vm = viewModel(repo, dailyNewLimit = 2)
        repeat(2) { vm.answer(Verdict.UNKNOWN) }
        vm.continueAfterBreak()

        val first = vm.testing().card
        vm.flipNow()
        vm.answer(Verdict.UNKNOWN)
        assertEquals(2L, vm.testing().card.item.wordId)

        vm.undo()

        val restored = vm.testing()
        assertEquals(first.uid, restored.card.uid)
        assertEquals("重排进去的那张要一并摘掉", 2, restored.remaining)
        assertEquals(listOf(1L), repo.restored)
        assertTrue("撤销后停在背面，可以直接重新判定", restored.card.flipped)
        assertEquals(SwipeDirection.UP, restored.undoReplay)
    }

    @Test
    fun `浏览阶段撤销不认识 - 完全不碰数据库`() {
        val repo = FakeStudyRepository(availableNew = newItems(1L..3L))
        val vm = viewModel(repo, dailyNewLimit = 3)

        vm.answer(Verdict.UNKNOWN)
        assertEquals(1, vm.browsing().selectedCount)

        vm.undo()

        assertTrue("那次操作没落库，撤销就不该删任何日志", repo.restored.isEmpty())
        assertEquals(1L, vm.browsing().card.item.wordId)
        assertEquals(0, vm.browsing().selectedCount)
        assertEquals(3, vm.browsing().remaining)
        assertEquals(SwipeDirection.UP, vm.browsing().undoReplay)
    }

    @Test
    fun `没有新词 - 直接进复习且不出拼写卡`() {
        val repo = FakeStudyRepository(due = (1L..4L).map { item(it, LearningStage.REVIEW) })

        val state = viewModel(repo).testing()

        assertEquals(TestPhase.REVIEW, state.phase)
        assertEquals(4, state.remaining)
        assertTrue(
            "复习只有英选中和中选英两种考法，实际是 ${state.card.kind}",
            state.card.kind != CardKind.SPELLING
        )
    }

    @Test
    fun `今天没东西可学 - 显示空状态`() {
        assertEquals(StudyUiState.Empty, viewModel(FakeStudyRepository()).uiState.value)
    }

    @Test
    fun `背完新词又没有到期复习 - 直接结束并汇报统计`() {
        val repo = FakeStudyRepository(availableNew = newItems(1L..2L))
        val vm = viewModel(repo, dailyNewLimit = 2)
        repeat(2) { vm.answer(Verdict.UNKNOWN) }
        vm.continueAfterBreak()

        vm.flipNow()
        vm.answer(Verdict.KNOWN)
        vm.flipNow()
        vm.answer(Verdict.UNKNOWN)
        vm.flipNow()
        vm.answer(Verdict.KNOWN)

        val finished = vm.uiState.value as StudyUiState.Finished
        assertEquals("同一个词答错重排后答对，只算一个新词", 2, finished.newCount)
        assertEquals(0, finished.reviewCount)
        assertEquals(1, finished.againCount)
        assertTrue(repo.restored.isEmpty())
    }

    @Test
    fun `上拉停驻露例句 - 下拉复位且不动队列`() {
        val repo = FakeStudyRepository(availableNew = newItems(1L..3L))
        val vm = viewModel(repo, dailyNewLimit = 3)

        vm.openReveal()
        assertTrue(vm.browsing().card.exampleRevealed)
        assertEquals(3, vm.browsing().remaining)

        vm.closeReveal()
        assertTrue(!vm.browsing().card.exampleRevealed)
        assertEquals(1L, vm.browsing().card.item.wordId)
    }

    @Test
    fun `左滑已学过 - 撤销时卡片从左边飞回`() {
        val repo = FakeStudyRepository(availableNew = newItems(1L..3L))
        val vm = viewModel(repo, dailyNewLimit = 3)

        vm.answer(Verdict.KNOWN)
        vm.undo()

        assertEquals(SwipeDirection.LEFT, vm.browsing().undoReplay)
        assertEquals(1L, vm.browsing().card.item.wordId)
        assertEquals(listOf(1L), repo.restored)
    }
}
