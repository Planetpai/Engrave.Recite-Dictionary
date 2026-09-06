package com.example.vocabwatch.ui.study

import com.example.vocabwatch.data.session.StudySessionStore
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
import com.example.vocabwatch.domain.usecase.RestoreSessionUseCase
import com.example.vocabwatch.domain.usecase.SubmitReviewUseCase
import com.example.vocabwatch.domain.usecase.UndoReviewUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val availableNew: List<StudyItem> = emptyList(),
        val againToday: List<StudyItem> = emptyList()
    ) : StudyRepository {
        var lastAgainLimit: Int = -1
        /** 落库过的评分：wordId 到 grade */
        val persisted = mutableListOf<Pair<Long, Grade>>()

        /** 撤销时恢复过状态的 wordId */
        val restored = mutableListOf<Long>()

        override fun observeStats(bookId: Long?): Flow<StudyStats> = flowOf(StudyStats())

        override suspend fun getDueItems(bookId: Long?, limit: Int, now: Long) = due.take(limit)

        override suspend fun getNewItems(bookId: Long?, limit: Int) = availableNew.take(limit)

        override suspend fun getItemsByIds(wordIds: List<Long>): List<StudyItem> =
            (due + availableNew).filter { it.wordId in wordIds }


        override suspend fun rescheduleToTomorrow(wordId: Long) = Unit

        override suspend fun rescheduleToTomorrowByTerm(term: String) = Unit

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

    private class FakeMistakeDao : com.example.vocabwatch.data.local.dao.MistakeDao {
        val added = mutableListOf<Pair<String, String?>>()
        val removed = mutableListOf<String>()
        override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<com.example.vocabwatch.data.local.entity.MistakeEntity>())
        override fun observeExists(term: String) = kotlinx.coroutines.flow.flowOf(false)
        override suspend fun exists(term: String) = false
        override suspend fun upsert(entry: com.example.vocabwatch.data.local.entity.MistakeEntity) {
            added.add(entry.term to entry.meaning)
        }
        override suspend fun delete(term: String) {
            removed.add(term)
        }
    }

    private class FakeSettings(config: StudyQueueConfig) : SettingsRepository {
        override val queueConfig: Flow<StudyQueueConfig> = flowOf(config)
        override val profile = flowOf(com.example.vocabwatch.domain.model.UserProfile())
        override val avatarImageVersion = flowOf(0)
        override val onboardingDone = flowOf(true)
        override val definitionDisplay =
            flowOf(com.example.vocabwatch.domain.model.DefinitionDisplay.BOTH)
        override val reminderMinutes = flowOf<Int?>(null)
        override suspend fun setDailyNewLimit(limit: Int) = Unit
        override suspend fun setActiveBook(bookId: Long?) = Unit
        override suspend fun setUserName(name: String) = Unit
        override suspend fun setAvatarIndex(index: Int) = Unit
        override suspend fun bumpAvatarImageVersion() = Unit
        override suspend fun resetAll() = Unit
        override suspend fun completeOnboarding() = Unit
        override suspend fun setDefinitionDisplay(display: com.example.vocabwatch.domain.model.DefinitionDisplay) = Unit
        override suspend fun setReminderMinutes(minutesOfDay: Int?) = Unit
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

    private val fakeMistakeDao = FakeMistakeDao()

    private fun viewModel(
        repo: FakeStudyRepository,
        dailyNewLimit: Int = 10
    ) = StudyViewModel(
        getStudySession = GetStudySessionUseCase(repo, timeProvider),
        submitReview = SubmitReviewUseCase(repo, Sm2Scheduler(), timeProvider),
        undoReview = UndoReviewUseCase(repo),
        settingsRepository = FakeSettings(
            StudyQueueConfig(dailyNewLimit = dailyNewLimit)
        ),
        timeProvider = timeProvider,
        sessionStore = StudySessionStore(
            File(System.getProperty("java.io.tmpdir"), "study-session-test-${System.nanoTime()}.json").parentFile
        ),
        restoreSession = RestoreSessionUseCase(repo),
        mistakeDao = fakeMistakeDao
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

        // 批次含缓冲（3 + 30，取词书里现有的 5 个），目标仍是 3
        assertEquals(5, vm.browsing().remaining)

        vm.answer(Verdict.KNOWN)

        assertEquals(listOf(1L to Grade.EASY), repo.persisted)
        assertEquals(4, vm.browsing().remaining)
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
        assertEquals("开始背单词", prompt.primaryLabel)
        assertTrue(repo.persisted.isEmpty())
    }

    @Test
    fun `开始背单词后 - 每个词刷满三遍且首遍从英文面开始`() {
        val repo = FakeStudyRepository(availableNew = newItems(1L..6L))
        val vm = viewModel(repo, dailyNewLimit = 3)
        repeat(3) { vm.answer(Verdict.UNKNOWN) }

        vm.onBreakAction(StudyUiState.BreakAction.START_FORMAL)

        val state = vm.testing()
        assertEquals(TestPhase.FORMAL, state.phase)
        assertEquals(3, state.remaining)
        assertEquals("第一遍是英文面", CardKind.EN_TO_ZH, state.card.kind)

        // 答会也沉底排下一遍：三个词都过完第一遍后，开始第二遍中文面
        vm.flipNow()
        vm.answer(Verdict.KNOWN)
        assertEquals(CardKind.EN_TO_ZH, vm.testing().card.kind)
        assertEquals("答会不会让队列变短，这个词还要再来两遍", 3, vm.testing().remaining)
        vm.flipNow()
        vm.answer(Verdict.KNOWN)
        assertEquals(CardKind.EN_TO_ZH, vm.testing().card.kind)
        vm.flipNow()
        vm.answer(Verdict.KNOWN)
        assertEquals("第二遍先显示中文", CardKind.ZH_TO_EN, vm.testing().card.kind)
    }

    @Test
    fun `第二遍中文第三遍拼写 - 乱序字母和正确答案等长同字母但不同序`() {
        val repo = FakeStudyRepository(availableNew = newItems(1L..6L))
        val vm = viewModel(repo, dailyNewLimit = 3)
        repeat(3) { vm.answer(Verdict.UNKNOWN) }
        vm.onBreakAction(StudyUiState.BreakAction.START_FORMAL)

        // 第一遍：三个词全答会
        repeat(3) {
            vm.flipNow()
            vm.answer(Verdict.KNOWN)
        }

        // 第二遍 word1：中文面
        val second = vm.testing().card
        assertEquals(1L, second.item.wordId)
        assertEquals(CardKind.ZH_TO_EN, second.kind)
        repeat(3) {
            vm.flipNow()
            vm.answer(Verdict.KNOWN)
        }

        // 第三遍 word1：拼写
        val spelling = vm.testing().card
        assertEquals(1L, spelling.item.wordId)
        assertEquals(CardKind.SPELLING, spelling.kind)
        // word1 里的数字不算字母，词长不超过上限就逐字母拼
        assertEquals(listOf("w", "o", "r", "d"), spelling.answer)
        assertEquals(spelling.answer.size, spelling.scrambled.size)
        assertEquals(spelling.answer.sorted(), spelling.scrambled.sorted())
        assertTrue("乱序不能正好是答案本身", spelling.scrambled != spelling.answer)
    }

    @Test
    fun `答不会的卡 - 归零沉底回英文面`() {
        val repo = FakeStudyRepository(availableNew = newItems(1L..6L))
        val vm = viewModel(repo, dailyNewLimit = 3)
        repeat(3) { vm.answer(Verdict.UNKNOWN) }
        vm.onBreakAction(StudyUiState.BreakAction.START_FORMAL)

        vm.flipNow()
        vm.answer(Verdict.UNKNOWN)

        assertEquals(listOf(1L to Grade.AGAIN), repo.persisted)
        // 沉到最底层：紧接着出现的仍是下一个词，总数不变
        assertEquals(2L, vm.testing().card.item.wordId)
        assertEquals(3, vm.testing().remaining)

        vm.flipNow()
        vm.answer(Verdict.KNOWN)
        vm.flipNow()
        vm.answer(Verdict.KNOWN)

        val replay = vm.testing().card
        assertEquals(1L, replay.item.wordId)
        assertEquals("答不会归零，重新从英文面开始", CardKind.EN_TO_ZH, replay.kind)
        assertEquals("重排的卡要带着刚算出来的新状态", LearningStage.LEARNING, replay.item.state.stage)
    }

    @Test
    fun `双击撤销 - 原卡回队首且沉底的那张被摘掉`() {
        val repo = FakeStudyRepository(availableNew = newItems(1L..4L))
        val vm = viewModel(repo, dailyNewLimit = 2)
        repeat(2) { vm.answer(Verdict.UNKNOWN) }
        vm.onBreakAction(StudyUiState.BreakAction.START_FORMAL)

        val first = vm.testing().card
        vm.flipNow()
        vm.answer(Verdict.UNKNOWN)
        assertEquals(2L, vm.testing().card.item.wordId)

        vm.undo()

        val restored = vm.testing()
        assertEquals(first.uid, restored.card.uid)
        assertEquals("沉底进去的那张要一并摘掉", 2, restored.remaining)
        assertEquals(listOf(1L), repo.restored)
        assertEquals("考法跟着它原来的第几刷走", first.kind, restored.card.kind)
        assertFalse("撤销后停在正面，不用再翻一次", restored.card.flipped)
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
    fun `没有新词 - 直接进复习且同样从英文面开始`() {
        val repo = FakeStudyRepository(due = (1L..4L).map { item(it, LearningStage.REVIEW) })

        val state = viewModel(repo).testing()

        assertEquals(TestPhase.REVIEW, state.phase)
        assertEquals(4, state.remaining)
        assertEquals("复习的第一刷也是英文面", CardKind.EN_TO_ZH, state.card.kind)
    }

    @Test
    fun `继续再学 - 忽略每日额度直接抽新词`() = kotlinx.coroutines.test.runTest {
        val repo = FakeStudyRepository(availableNew = newItems(100L..105L))
        val vm = viewModel(repo, dailyNewLimit = 2)

        vm.continueLearning(5)

        val state = vm.browsing()
        assertEquals("继续再学进入浏览选词（学新单词）", 6, state.remaining)
        assertEquals("目标是用户选的数量", 5, state.selectionTarget)
    }

    @Test
    fun `今天没东西可学 - 显示空状态`() {
        assertEquals(StudyUiState.Empty, viewModel(FakeStudyRepository()).uiState.value)
    }

    @Test
    fun `背完新词又没有到期复习 - 三遍走完直接结束并汇报统计`() {
        val repo = FakeStudyRepository(availableNew = newItems(1L..2L))
        val vm = viewModel(repo, dailyNewLimit = 2)
        repeat(2) { vm.answer(Verdict.UNKNOWN) }
        vm.onBreakAction(StudyUiState.BreakAction.START_FORMAL)

        // word1 一路答会，三遍走完出队；word2 中途答错一次，归零重刷
        vm.flipNow()
        vm.answer(Verdict.KNOWN)
        vm.flipNow()
        vm.answer(Verdict.UNKNOWN)
        vm.flipNow()
        vm.answer(Verdict.KNOWN)
        vm.flipNow()
        vm.answer(Verdict.KNOWN)
        vm.flipNow()
        vm.answer(Verdict.KNOWN)
        vm.flipNow()
        vm.answer(Verdict.KNOWN)
        vm.flipNow()
        vm.answer(Verdict.KNOWN)

        val finished = vm.uiState.value as StudyUiState.Finished
        assertEquals("同一个词刷满三遍只算一个新词", 2, finished.newCount)
        assertEquals(0, finished.reviewCount)
        assertEquals(1, finished.againCount)
        assertTrue(repo.restored.isEmpty())
    }

    @Test
    fun `复习阶段 - 每个词同样刷满英中拼三遍`() {
        val repo = FakeStudyRepository(due = (1L..2L).map { item(it, LearningStage.REVIEW) })
        val vm = viewModel(repo)

        assertEquals(CardKind.EN_TO_ZH, vm.testing().card.kind)
        vm.flipNow()
        vm.answer(Verdict.KNOWN)
        assertEquals("答会沉底排下一遍，先过别的词", 2L, vm.testing().card.item.wordId)
        vm.flipNow()
        vm.answer(Verdict.KNOWN)

        val second = vm.testing().card
        assertEquals(1L, second.item.wordId)
        assertEquals("第二遍中文面", CardKind.ZH_TO_EN, second.kind)
        vm.flipNow()
        vm.answer(Verdict.KNOWN)
        vm.flipNow()
        vm.answer(Verdict.KNOWN)

        val spelling = vm.testing().card
        assertEquals(1L, spelling.item.wordId)
        assertEquals("第三遍拼写", CardKind.SPELLING, spelling.kind)
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
