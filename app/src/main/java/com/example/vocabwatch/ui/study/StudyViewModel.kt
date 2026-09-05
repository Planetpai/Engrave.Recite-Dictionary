package com.example.vocabwatch.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vocabwatch.domain.model.Grade
import com.example.vocabwatch.domain.model.ReviewState
import com.example.vocabwatch.domain.model.StudyItem
import com.example.vocabwatch.domain.repository.SettingsRepository
import com.example.vocabwatch.domain.srs.TimeProvider
import com.example.vocabwatch.domain.usecase.GetStudySessionUseCase
import com.example.vocabwatch.domain.usecase.SubmitReviewUseCase
import com.example.vocabwatch.domain.usecase.UndoReviewUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 学习页状态机：浏览选词 → 正式测试 → 复习。
 *
 * 队列是内存快照而不是 Flow。若直接观察数据库，刚答对的卡片会因为 dueAt
 * 变化立刻从队列里消失，进度和翻页就乱了。每次评分照常落库，所以中途退出
 * 不需要额外保存进度。
 *
 * 答错的卡换一种考法、隔 REQUEUE_GAP 张重新插入当前队列；双击撤销会把重排
 * 进去的那张按 uid 摘掉，再把原卡放回队首，并回滚数据库里的状态和日志。
 */
class StudyViewModel(
    private val getStudySession: GetStudySessionUseCase,
    private val submitReview: SubmitReviewUseCase,
    private val undoReview: UndoReviewUseCase,
    private val settingsRepository: SettingsRepository,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow<StudyUiState>(StudyUiState.Loading)
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

    private val browseQueue = ArrayDeque<StudyItem>()
    private val selected = ArrayList<StudyItem>()
    private val testQueue = ArrayDeque<TestCard>()
    private val undoStack = ArrayList<UndoEntry>()

    /** 本轮到期复习的词，进入复习阶段前不动它 */
    private var dueItems: List<StudyItem> = emptyList()
    private var selectionTarget = 0
    private var phase = TestPhase.FORMAL
    private var breakKind = BreakKind.START_FORMAL
    private var cardShownAt = 0L
    private var nextUid = 0L

    /** 落库请求在飞的时候吃掉所有输入，防止连点把队列搞乱 */
    private var busy = false

    private val newWords = LinkedHashSet<Long>()
    private val reviewWords = LinkedHashSet<Long>()
    private var againCount = 0

    init {
        startSession()
    }

    fun startSession() {
        viewModelScope.launch {
            _uiState.value = StudyUiState.Loading
            val config = settingsRepository.queueConfig.first()
            val plan = getStudySession(config)

            reset()
            browseQueue.addAll(plan.newItems)
            dueItems = plan.dueItems
            selectionTarget = plan.newItems.size

            when {
                plan.isEmpty -> _uiState.value = StudyUiState.Empty
                browseQueue.isNotEmpty() -> emitBrowse()
                else -> startReviewPhase()
            }
        }
    }

    private fun reset() {
        browseQueue.clear()
        selected.clear()
        testQueue.clear()
        undoStack.clear()
        newWords.clear()
        reviewWords.clear()
        dueItems = emptyList()
        selectionTarget = 0
        againCount = 0
        busy = false
    }

    // ------------------------------------------------------------------ 用户操作

    /** 上拉卡片露出例句，只在浏览阶段有意义 */
    fun revealExample() {
        val state = _uiState.value as? StudyUiState.Browsing ?: return
        if (!state.card.exampleRevealed) {
            _uiState.value = state.copy(card = state.card.copy(exampleRevealed = true))
        }
    }

    /** 点击翻面。拼写卡没有背面，直接拼字母。 */
    fun flip() {
        val state = _uiState.value as? StudyUiState.Testing ?: return
        val card = state.card
        if (card.kind == CardKind.SPELLING || card.flipped) return
        // 入场保护期：补完拼写的那一下点击不该把刚出现的下一张卡翻过来
        if (timeProvider.now() - cardShownAt < FLIP_GRACE_MILLIS) return
        val flipped = card.copy(flipped = true)
        testQueue[0] = flipped
        _uiState.value = state.copy(card = flipped)
    }

    fun answer(verdict: Verdict) {
        if (busy) return
        when (_uiState.value) {
            is StudyUiState.Browsing -> answerBrowse(verdict)
            is StudyUiState.Testing -> answerTest(verdict)
            else -> Unit
        }
    }

    /** 双击撤销上一张卡。提示页和完成页不响应。 */
    fun undo() {
        if (busy) return
        val state = _uiState.value
        if (state !is StudyUiState.Browsing && state !is StudyUiState.Testing) return
        val entry = undoStack.removeLastOrNull() ?: return

        busy = true
        viewModelScope.launch {
            undoReview(entry.wordId, entry.stateBefore)
            when (entry) {
                is UndoEntry.Browse -> {
                    if (entry.verdict == Verdict.UNKNOWN) selected.remove(entry.item)
                    browseQueue.addFirst(entry.item)
                    busy = false
                    emitBrowse(entry.direction)
                }

                is UndoEntry.Test -> {
                    entry.requeuedUid?.let { uid -> testQueue.removeAll { it.uid == uid } }
                    testQueue.addFirst(entry.card)
                    if (entry.phase == TestPhase.FORMAL) {
                        newWords.remove(entry.wordId)
                    } else {
                        reviewWords.remove(entry.wordId)
                    }
                    if (entry.verdict == Verdict.UNKNOWN) againCount--
                    busy = false
                    emitTest(entry.direction)
                }
            }
        }
    }

    fun continueAfterBreak() {
        when (breakKind) {
            BreakKind.START_FORMAL -> startFormalPhase()
            BreakKind.START_REVIEW -> startReviewPhase()
        }
    }

    // ------------------------------------------------------------------ 浏览阶段

    private fun answerBrowse(verdict: Verdict) {
        val item = browseQueue.removeFirst()
        val direction = if (verdict == Verdict.KNOWN) SwipeDirection.RIGHT else SwipeDirection.UP

        if (verdict == Verdict.UNKNOWN) {
            // 「不认识」这一步不写库：真正的第一次评分发生在正式测试阶段
            selected.add(item)
            undoStack.add(UndoEntry.Browse(item.wordId, null, direction, verdict, item))
            advanceBrowse()
            return
        }

        // 「已学过」按 EASY 落库，直接排到几天后，也不再占今天的新词额度
        val elapsed = elapsed()
        busy = true
        viewModelScope.launch {
            submitReview(item, Grade.EASY, elapsed)
            newWords.add(item.wordId)
            undoStack.add(UndoEntry.Browse(item.wordId, item.state, direction, verdict, item))
            busy = false
            advanceBrowse()
        }
    }

    private fun advanceBrowse() {
        if (browseQueue.isEmpty() && selected.isEmpty()) {
            startReviewPhase()
            return
        }
        if (selected.size >= selectionTarget || browseQueue.isEmpty()) {
            breakTo(BreakKind.START_FORMAL)
        } else {
            emitBrowse()
        }
    }

    private fun emitBrowse(replay: SwipeDirection? = null) {
        cardShownAt = timeProvider.now()
        _uiState.value = StudyUiState.Browsing(
            card = BrowseCard(browseQueue.first(), exampleRevealed = false),
            behindCount = (browseQueue.size - 1).coerceIn(0, MAX_BEHIND),
            remaining = browseQueue.size,
            selectedCount = selected.size,
            selectionTarget = selectionTarget,
            undoReplay = replay
        )
    }

    // ------------------------------------------------------------------ 测试阶段

    private fun startFormalPhase() {
        phase = TestPhase.FORMAL
        val kinds = kindsFor(phase)
        testQueue.clear()
        // 换阶段就清空撤销栈：撤销不该把新词卡塞回已经切换走的复习队列
        undoStack.clear()
        selected.forEachIndexed { index, item ->
            testQueue.addLast(makeCard(item, kinds[index % kinds.size]))
        }
        selected.clear()
        if (testQueue.isEmpty()) startReviewPhase() else emitTest()
    }

    private fun startReviewPhase() {
        phase = TestPhase.REVIEW
        val kinds = kindsFor(phase)
        testQueue.clear()
        undoStack.clear()
        dueItems.forEachIndexed { index, item ->
            testQueue.addLast(makeCard(item, kinds[index % kinds.size]))
        }
        dueItems = emptyList()
        if (testQueue.isEmpty()) finish() else emitTest()
    }

    private fun answerTest(verdict: Verdict) {
        val card = testQueue.removeFirst()
        val item = card.item
        val direction = if (verdict == Verdict.KNOWN) SwipeDirection.RIGHT else SwipeDirection.UP
        val grade = if (verdict == Verdict.KNOWN) Grade.GOOD else Grade.AGAIN
        val elapsed = elapsed()

        busy = true
        viewModelScope.launch {
            val outcome = submitReview(item, grade, elapsed)
            if (phase == TestPhase.FORMAL) newWords.add(item.wordId) else reviewWords.add(item.wordId)
            if (verdict == Verdict.UNKNOWN) againCount++

            var requeuedUid: Long? = null
            if (verdict == Verdict.UNKNOWN) {
                // 答错就重置重排：换一种考法，带着刚算出来的新状态隔两张再来
                val requeue = makeCard(item.copy(state = outcome.newState), nextKindFor(phase, card.kind))
                requeuedUid = requeue.uid
                testQueue.add(min(REQUEUE_GAP, testQueue.size), requeue)
            }

            undoStack.add(UndoEntry.Test(item.wordId, item.state, direction, verdict, phase, card, requeuedUid))
            busy = false
            advanceTest()
        }
    }

    private fun advanceTest() {
        if (testQueue.isNotEmpty()) {
            emitTest()
            return
        }
        when (phase) {
            TestPhase.FORMAL -> if (dueItems.isNotEmpty()) breakTo(BreakKind.START_REVIEW) else finish()
            TestPhase.REVIEW -> finish()
        }
    }

    private fun emitTest(replay: SwipeDirection? = null) {
        cardShownAt = timeProvider.now()
        _uiState.value = StudyUiState.Testing(
            phase = phase,
            card = testQueue.first(),
            behindCount = (testQueue.size - 1).coerceIn(0, MAX_BEHIND),
            remaining = testQueue.size,
            undoReplay = replay
        )
    }

    private fun makeCard(item: StudyItem, kind: CardKind): TestCard {
        val answer = if (kind == CardKind.SPELLING) spellingLetters(item.word.term) else emptyList()
        return TestCard(
            uid = nextUid++,
            item = item,
            kind = kind,
            answer = answer,
            scrambled = if (answer.isEmpty()) emptyList() else shuffleAwayFrom(answer)
        )
    }

    // ------------------------------------------------------------------ 收尾

    private fun elapsed(): Long = (timeProvider.now() - cardShownAt).coerceAtLeast(0L)

    private fun breakTo(kind: BreakKind) {
        breakKind = kind
        _uiState.value = when (kind) {
            BreakKind.START_FORMAL -> StudyUiState.Break(
                title = "选够了",
                subtitle = "已选 ${selected.size} 个新词",
                actionLabel = "开始背单词"
            )

            BreakKind.START_REVIEW -> StudyUiState.Break(
                title = "新词完成",
                subtitle = "还有 ${dueItems.size} 个到期复习",
                actionLabel = "开始复习"
            )
        }
    }

    private fun finish() {
        _uiState.value = StudyUiState.Finished(
            newCount = newWords.size,
            reviewCount = reviewWords.size,
            againCount = againCount
        )
    }

    private enum class BreakKind { START_FORMAL, START_REVIEW }

    private sealed interface UndoEntry {
        val wordId: Long

        /** null 表示被撤销的那次操作没有落库 */
        val stateBefore: ReviewState?
        val direction: SwipeDirection
        val verdict: Verdict

        data class Browse(
            override val wordId: Long,
            override val stateBefore: ReviewState?,
            override val direction: SwipeDirection,
            override val verdict: Verdict,
            val item: StudyItem
        ) : UndoEntry

        data class Test(
            override val wordId: Long,
            override val stateBefore: ReviewState?,
            override val direction: SwipeDirection,
            override val verdict: Verdict,
            val phase: TestPhase,
            val card: TestCard,
            val requeuedUid: Long?
        ) : UndoEntry
    }

    private companion object {
        /** 卡片背后叠几张，纯视觉 */
        const val MAX_BEHIND = 2

        /** 答错的卡隔多少张再出现 */
        const val REQUEUE_GAP = 2

        /** 卡片刚出现时的翻面保护期，吃掉上一张卡遗留的点击 */
        const val FLIP_GRACE_MILLIS = 250L

        val FORMAL_KINDS = listOf(CardKind.EN_TO_ZH, CardKind.ZH_TO_EN, CardKind.SPELLING)
        val REVIEW_KINDS = listOf(CardKind.EN_TO_ZH, CardKind.ZH_TO_EN)

        fun kindsFor(phase: TestPhase): List<CardKind> =
            if (phase == TestPhase.FORMAL) FORMAL_KINDS else REVIEW_KINDS

        /** 重排时换一种考法，避免同一张卡原样再来一遍 */
        fun nextKindFor(phase: TestPhase, avoid: CardKind): CardKind =
            kindsFor(phase).filter { it != avoid }.random()

        /** 拼写卡只考字母，所以丢掉空格和连字符（"give up" 拼成 "giveup"）。 */
        fun spellingLetters(term: String): List<Char> = term.lowercase().filter { it.isLetter() }.toList()

        /** 洗乱字母；如果正好洗出原顺序就重洗，否则拼写卡一眼就看穿了。 */
        fun shuffleAwayFrom(letters: List<Char>): List<Char> {
            if (letters.size < 2) return letters
            var shuffled = letters.shuffled()
            var attempts = 0
            while (shuffled == letters && attempts++ < 8) shuffled = letters.shuffled()
            return shuffled
        }
    }
}
