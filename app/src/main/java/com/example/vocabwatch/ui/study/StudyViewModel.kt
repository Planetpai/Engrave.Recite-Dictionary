package com.example.vocabwatch.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vocabwatch.data.local.dao.MistakeDao
import com.example.vocabwatch.data.local.entity.MistakeEntity
import com.example.vocabwatch.data.session.StudySessionStore
import com.example.vocabwatch.domain.model.Grade
import com.example.vocabwatch.domain.model.ReviewState
import com.example.vocabwatch.domain.model.StudyItem
import com.example.vocabwatch.domain.repository.SettingsRepository
import com.example.vocabwatch.domain.srs.TimeProvider
import com.example.vocabwatch.domain.usecase.GetStudySessionUseCase
import com.example.vocabwatch.domain.usecase.RestoreSessionUseCase
import com.example.vocabwatch.domain.usecase.SubmitReviewUseCase
import com.example.vocabwatch.domain.usecase.UndoReviewUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 学习页状态机：浏览选词 → 正式测试 → 复习。
 *
 * 队列是内存快照而不是 Flow。若直接观察数据库，刚答对的卡片会因为 dueAt
 * 变化立刻从队列里消失，进度和翻页就乱了。
 *
 * **断点续传**：每次评分虽然落库，但「这轮还剩哪些卡」只活在内存里，中途
 * 退出再进来按当日额度重算会得到空队列，于是「明明没背完却提示学完了」。
 * 每次状态发射都会把队列快照写进 [StudySessionStore]，重新进入时优先恢复
 * 快照（只对本日有效），恢复不了才按额度开新一轮。
 *
 * 考法跟着「刷到第几遍」走：每个词固定刷满三遍——第一遍英文面、第二遍
 * 中文面、第三遍拼写。答会进入下一遍，答不会归零回第一遍；两种情况都沉到
 * 队列最底层，把没刷完的先过完再回来，拼写通过（第三遍答会）才出队。
 * 双击撤销会把沉底的那张按 uid 摘掉，再把原卡放回队首（停在正面），并回滚
 * 数据库里的状态和日志（撤销栈不跨会话保留）。
 */
class StudyViewModel(
    private val getStudySession: GetStudySessionUseCase,
    private val submitReview: SubmitReviewUseCase,
    private val undoReview: UndoReviewUseCase,
    private val settingsRepository: SettingsRepository,
    private val timeProvider: TimeProvider,
    private val sessionStore: StudySessionStore,
    private val restoreSession: RestoreSessionUseCase,
    private val mistakeDao: MistakeDao
) : ViewModel() {
    /** 长按加入错题集成功的时间戳，界面借此闪一下提示。 */
    private val _mistakeFlash = MutableStateFlow(0L)
    val mistakeFlash: StateFlow<Long> = _mistakeFlash.asStateFlow()

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

    /** fresh=true 时忽略快照，强制开新一轮（「再来一组」场景）。 */
    fun startSession(fresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = StudyUiState.Loading
            if (!fresh) {
                val snapshot = sessionStore.load()
                if (snapshot != null && snapshot.hasContent) {
                    restore(snapshot)
                    return@launch
                }
            }

            val config = settingsRepository.queueConfig.first()
            val plan = getStudySession(config)

            reset()
            browseQueue.addAll(plan.newItems)
            dueItems = plan.dueItems
            // 目标 = 每日额度；批次里的多余词是给「已学过」跳过用的
            selectionTarget = plan.selectionTarget

            when {
                plan.isEmpty -> {
                    sessionStore.clear()
                    _uiState.value = StudyUiState.Empty
                }
                browseQueue.isNotEmpty() -> emitBrowse()
                else -> startReviewPhase()
            }
        }
    }

    /**
     * 「继续再学」：忽略每日额度，从词书里抽 [count] 个**新单词**，
     * 进入浏览选词 → 背单词的完整流程。词书抽空时回到空状态。
     */
    fun continueLearning(count: Int) {
        viewModelScope.launch {
            _uiState.value = StudyUiState.Loading
            val config = settingsRepository.queueConfig.first()
            val plan = getStudySession(config, newLimitOverride = count)

            reset()
            browseQueue.addAll(plan.newItems)
            dueItems = plan.dueItems
            selectionTarget = plan.selectionTarget

            when {
                browseQueue.isNotEmpty() -> emitBrowse()
                dueItems.isNotEmpty() -> startReviewPhase()
                else -> _uiState.value = StudyUiState.Empty
            }
        }
    }

    /** 按快照重建队列。缺词条（词书被删等）直接丢弃。 */
    private suspend fun restore(snapshot: StudySessionStore.Snapshot) {
        reset()
        val ids = snapshot.browseQueue + snapshot.selected + snapshot.dueItems +
            snapshot.newWords + snapshot.reviewWords + snapshot.testQueue.map { it.wordId }
        val items = restoreSession(ids.distinct())

        browseQueue.addAll(snapshot.browseQueue.mapNotNull { items[it] })
        selected.addAll(snapshot.selected.mapNotNull { items[it] })
        dueItems = snapshot.dueItems.mapNotNull { items[it] }
        snapshot.newWords.forEach { if (items.containsKey(it)) newWords.add(it) }
        snapshot.reviewWords.forEach { if (items.containsKey(it)) reviewWords.add(it) }
        selectionTarget = snapshot.selectionTarget
        againCount = snapshot.againCount
        snapshot.testQueue.forEach { card ->
            items[card.wordId]?.let { item ->
                testQueue.add(makeCard(item, card.encounter).copy(flipped = card.flipped))
            }
        }

        when (snapshot.phase) {
            PHASE_BROWSING -> emitBrowse()

            PHASE_FORMAL -> {
                phase = TestPhase.FORMAL
                emitTest()
            }

            PHASE_REVIEW -> {
                phase = TestPhase.REVIEW
                emitTest()
            }

            PHASE_BREAK_FORMAL -> {
                phase = TestPhase.FORMAL
                showBreak(BreakKind.START_FORMAL, snapshot.breakTitle, snapshot.breakSubtitle)
            }

            PHASE_BREAK_REVIEW -> {
                phase = TestPhase.REVIEW
                showBreak(BreakKind.START_REVIEW, snapshot.breakTitle, snapshot.breakSubtitle)
            }

            else -> emitBrowse()
        }
        persist()
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

    /** 学习页长按卡片：把当前词加入错题集（重复长按幂等）。 */
    fun addCurrentToMistakes() {
        val item = when (val state = _uiState.value) {
            is StudyUiState.Testing -> state.card.item
            is StudyUiState.Browsing -> state.card.item
            else -> return
        }
        viewModelScope.launch {
            mistakeDao.upsert(
                MistakeEntity(
                    term = item.word.term,
                    meaning = item.word.meaning.lineSequence().firstOrNull(),
                    phonetic = item.word.phonetic,
                    addedAt = timeProvider.now()
                )
            )
            _mistakeFlash.value = timeProvider.now()
        }
    }

    fun onBreakAction(action: StudyUiState.BreakAction) {
        when (action) {
            StudyUiState.BreakAction.START_FORMAL -> startFormalPhase()
            StudyUiState.BreakAction.START_REVIEW -> startReviewPhase()
            StudyUiState.BreakAction.CONTINUE_LEARNING, StudyUiState.BreakAction.REST -> Unit
        }
    }

    /** 点击翻面。拼写卡也能翻：正面释义、背面选词填空，反复切换。 */
    fun flip() {
        val state = _uiState.value as? StudyUiState.Testing ?: return
        val card = state.card
        if (card.kind != CardKind.SPELLING && card.flipped) return
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

    // ------------------------------------------------------------------ 浏览阶段

    private fun answerBrowse(verdict: Verdict) {
        val item = browseQueue.removeFirst()
        val direction = if (verdict == Verdict.KNOWN) SwipeDirection.LEFT else SwipeDirection.UP

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
        // 攒够目标数量的「不认识」词才进背单词；批次提前抽完也放行（词书见底）
        if (selected.size >= selectionTarget) {
            breakTo(BreakKind.START_FORMAL)
            return
        }
        if (browseQueue.isEmpty()) {
            breakTo(BreakKind.START_FORMAL)
            return
        }
        emitBrowse()
    }

    private fun emitBrowse(replay: SwipeDirection? = null) {
        cardShownAt = timeProvider.now()
        _uiState.value = StudyUiState.Browsing(
            card = BrowseCard(browseQueue.first(), exampleRevealed = false),
            behindCount = (browseQueue.size - 1).coerceIn(0, MAX_BEHIND),
            nextItems = browseQueue.toList().drop(1).take(MAX_BEHIND),
            remaining = browseQueue.size,
            selectedCount = selected.size,
            selectionTarget = selectionTarget,
            undoReplay = replay
        )
        persist()
    }

    // ------------------------------------------------------------------ 测试阶段

    private fun startFormalPhase() {
        phase = TestPhase.FORMAL
        testQueue.clear()
        // 换阶段就清空撤销栈：撤销不该把新词卡塞回已经切换走的复习队列
        undoStack.clear()
        // 每个词在本阶段的第一遍都从英文面开始
        selected.forEach { item -> testQueue.addLast(makeCard(item, FIRST_PASS)) }
        selected.clear()
        if (testQueue.isEmpty()) startReviewPhase() else emitTest()
    }

    private fun startReviewPhase() {
        phase = TestPhase.REVIEW
        testQueue.clear()
        undoStack.clear()
        // 复习同样遵守 英文面 → 中文面 → 拼写 的次序，每个词刷满三遍
        dueItems.forEach { item -> testQueue.addLast(makeCard(item, FIRST_PASS)) }
        dueItems = emptyList()
        if (testQueue.isEmpty()) finish() else emitTest()
    }

    private fun answerTest(verdict: Verdict) {
        val card = testQueue.removeFirst()
        val item = card.item
        val direction = if (verdict == Verdict.KNOWN) SwipeDirection.LEFT else SwipeDirection.UP
        val grade = if (verdict == Verdict.KNOWN) Grade.GOOD else Grade.AGAIN
        val elapsed = elapsed()

        busy = true
        viewModelScope.launch {
            val outcome = submitReview(item, grade, elapsed)
            if (phase == TestPhase.FORMAL) newWords.add(item.wordId) else reviewWords.add(item.wordId)
            if (verdict == Verdict.UNKNOWN) againCount++

            var requeuedUid: Long? = null
            // 会也刷满三遍：过完当前这遍沉底排队下一遍；不会归零回第一遍。
            // 第三遍拼写答会才算出队。
            val nextPass: Int? = when {
                verdict == Verdict.UNKNOWN -> FIRST_PASS
                card.encounter < KIND_CYCLE.size -> card.encounter + 1
                else -> null
            }
            nextPass?.let { pass ->
                val requeue = makeCard(item.copy(state = outcome.newState), pass)
                requeuedUid = requeue.uid
                testQueue.addLast(requeue)
            }

            // 撤销时卡片要回到它刚出现的样子：停在正面，而不是评分时翻开的背面
            undoStack.add(UndoEntry.Test(item.wordId, item.state, direction, verdict, phase, card.copy(flipped = false), requeuedUid))
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
            nextCards = testQueue.toList().drop(1).take(MAX_BEHIND),
            remaining = testQueue.size,
            undoReplay = replay
        )
        persist()
    }

    private fun makeCard(item: StudyItem, encounter: Int): TestCard {
        val kind = kindFor(encounter)
        val chunks = if (kind == CardKind.SPELLING) spellingChunks(item.word.term) else emptyList()
        return TestCard(
            uid = nextUid++,
            item = item,
            kind = kind,
            encounter = encounter,
            answer = chunks,
            scrambled = if (chunks.isEmpty()) emptyList() else shuffleAwayFrom(chunks)
        )
    }

    // ------------------------------------------------------------------ 收尾

    private fun elapsed(): Long = (timeProvider.now() - cardShownAt).coerceAtLeast(0L)

    private fun breakTo(kind: BreakKind) {
        val (title, subtitle) = when (kind) {
            BreakKind.START_FORMAL -> "选够了" to "已选 ${selected.size} 个新词"
            BreakKind.START_REVIEW -> "新词完成" to "还有 ${dueItems.size} 个到期复习"
        }
        showBreak(kind, title, subtitle)
    }

    private fun showBreak(kind: BreakKind, title: String?, subtitle: String?) {
        phase = if (kind == BreakKind.START_FORMAL) TestPhase.FORMAL else TestPhase.REVIEW
        _uiState.value = StudyUiState.Break(
            title = title ?: when (kind) {
                BreakKind.START_FORMAL -> "选够了"
                BreakKind.START_REVIEW -> "新词完成"
            },
            subtitle = subtitle ?: when (kind) {
                BreakKind.START_FORMAL -> "已选 ${selected.size} 个新词"
                BreakKind.START_REVIEW -> "还有 ${dueItems.size} 个到期复习"
            },
            primaryLabel = when (kind) {
                BreakKind.START_FORMAL -> "开始背单词"
                BreakKind.START_REVIEW -> "开始复习"
            },
            onPrimary = when (kind) {
                BreakKind.START_FORMAL -> StudyUiState.BreakAction.START_FORMAL
                BreakKind.START_REVIEW -> StudyUiState.BreakAction.START_REVIEW
            }
        )
        persist()
    }

    private fun finish() {
        sessionStore.clear()
        _uiState.value = StudyUiState.Finished(
            newCount = newWords.size,
            reviewCount = reviewWords.size,
            againCount = againCount
        )
    }

    /**
     * 把当前队列状态写进快照。在每次状态发射后调用；完成 / 空状态清掉快照，
     * 保证「今天学完了」的判断永远基于真实数据。
     */
    private fun persist() {
        val state = _uiState.value
        val phaseName: String
        val breakTitle: String?
        val breakSubtitle: String?
        when (state) {
            is StudyUiState.Browsing -> {
                phaseName = PHASE_BROWSING
                breakTitle = null
                breakSubtitle = null
            }

            is StudyUiState.Testing -> {
                phaseName = if (phase == TestPhase.FORMAL) PHASE_FORMAL else PHASE_REVIEW
                breakTitle = null
                breakSubtitle = null
            }

            is StudyUiState.Break -> {
                phaseName = if (breakKind(kind = state.onPrimary)) PHASE_BREAK_FORMAL else PHASE_BREAK_REVIEW
                breakTitle = state.title
                breakSubtitle = state.subtitle
            }

            else -> return
        }
        sessionStore.save(
            StudySessionStore.Snapshot(
                day = LocalDate.now().toEpochDay(),
                phase = phaseName,
                browseQueue = browseQueue.map { it.wordId },
                selected = selected.map { it.wordId },
                selectionTarget = selectionTarget,
                testQueue = testQueue.map {
                    StudySessionStore.Snapshot.CardSnapshot(it.item.wordId, it.encounter, it.flipped)
                },
                dueItems = dueItems.map { it.wordId },
                newWords = newWords.toList(),
                reviewWords = reviewWords.toList(),
                againCount = againCount,
                breakTitle = breakTitle,
                breakSubtitle = breakSubtitle
            )
        )
    }

    private fun breakKind(kind: StudyUiState.BreakAction): Boolean =
        kind == StudyUiState.BreakAction.START_FORMAL

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
        /** 卡片背后叠几张：只露下一张的单词/翻译，再往下的不显示 */
        const val MAX_BEHIND = 1

        /** 卡片刚出现时的翻面保护期，吃掉上一张卡遗留的点击 */
        const val FLIP_GRACE_MILLIS = 250L

        /** 一张卡的首遍永远是英文面 */
        const val FIRST_PASS = 1

        /** 考法按刷到的遍数轮换：第一遍英文、第二遍中文、第三遍拼写 */
        val KIND_CYCLE = listOf(CardKind.EN_TO_ZH, CardKind.ZH_TO_EN, CardKind.SPELLING)

        /** 快照里的阶段名。 */
        const val PHASE_BROWSING = "BROWSING"
        const val PHASE_FORMAL = "FORMAL"
        const val PHASE_REVIEW = "REVIEW"
        const val PHASE_BREAK_FORMAL = "BREAK_FORMAL"
        const val PHASE_BREAK_REVIEW = "BREAK_REVIEW"

        fun kindFor(encounter: Int): CardKind = KIND_CYCLE[(encounter - 1) % KIND_CYCLE.size]

        /** 拼写卡只考字母，所以丢掉空格和连字符（"give up" 拼成 "giveup"）。 */
        fun spellingLetters(term: String): List<Char> = term.lowercase().filter { it.isLetter() }.toList()

        /** 洗乱分块；如果正好洗出原顺序就重洗，否则拼写卡一眼就看穿了。 */
        fun <T> shuffleAwayFrom(items: List<T>): List<T> {
            if (items.size < 2) return items
            var shuffled = items.shuffled()
            var attempts = 0
            while (shuffled == items && attempts++ < 8) shuffled = items.shuffled()
            return shuffled
        }
    }
}
