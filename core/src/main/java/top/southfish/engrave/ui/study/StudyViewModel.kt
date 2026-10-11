package top.southfish.engrave.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.southfish.engrave.data.local.DayIndex
import top.southfish.engrave.data.local.dao.MistakeDao
import top.southfish.engrave.data.local.entity.MistakeEntity
import top.southfish.engrave.data.session.StudySessionStore
import top.southfish.engrave.domain.model.Grade
import top.southfish.engrave.domain.model.ReviewState
import top.southfish.engrave.domain.model.StudyItem
import top.southfish.engrave.domain.repository.SettingsRepository
import top.southfish.engrave.domain.srs.TimeProvider
import top.southfish.engrave.domain.usecase.GetStudySessionUseCase
import top.southfish.engrave.domain.usecase.RestoreSessionUseCase
import top.southfish.engrave.domain.usecase.SubmitReviewUseCase
import top.southfish.engrave.domain.usecase.UndoReviewUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
 *
 * **每个词每次会话只落一次 SRS 评分**：三遍考法是同一次学习里的强化，
 * 以前每一遍滑动都提交评分，新词的三次 GOOD 会在几分钟内连走完
 * 「1 小时学习步 → 毕业」，分钟级到期时间被立刻覆盖，于是「学完 1 小时
 * 后复习」永远不会出现。现在只有离队的那一下写库：
 *   - 正式阶段（新词）：无论中途卡壳几次，统一 GOOD → 排到 1 小时后；
 *   - 复习阶段（已学词）：中途答错过一次就按 AGAIN（打回 1 小时重记），
 *     一遍不错才按 GOOD 进阶。
 * 中途的「不会」只在内存的 [failureCounts] 里计数，不碰数据库和 ease。
 *
 * 双击卡片外区域撤销会把沉底的那张按 uid 摘掉，再把原卡放回队首（停在
 * 正面），并回滚数据库里的状态和日志（撤销栈不跨会话保留）。
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

    /**
     * 单词发音开关：学习卡片展示英文词头时是否自动朗读（仅学习卡片场景，
     * 详情页点词头朗读不受它控制）。
     */
    val ttsEnabled: StateFlow<Boolean> = settingsRepository.ttsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _uiState = MutableStateFlow<StudyUiState>(StudyUiState.Loading)
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

    private val browseQueue = ArrayDeque<StudyItem>()
    private val selected = ArrayList<StudyItem>()
    private val testQueue = ArrayDeque<TestCard>()
    private val undoStack = ArrayList<UndoEntry>()

    /**
     * 浏览阶段已经见过的词 id（队列里的 + 已入选的 + 已左滑落库的）。
     * 续拉下一批时用来排除：上滑「不认识」的词不写库、阶段仍是 NEW，
     * 不排除会被重复抽回来。
     */
    private val browseSeen = LinkedHashSet<Long>()

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

    /**
     * 复习阶段里每个词本会话「答不会」的次数（按 wordId 计数）。
     * 词离队时据此决定落 GOOD 还是 AGAIN；撤销一次「不会」就减一。
     * 随快照持久化，保证进程被杀后续学仍能正确判定。
     */
    private val failureCounts = HashMap<Long, Int>()

    init {
        startSession()
    }

    /** fresh=true 时忽略快照，强制开新一轮（「再来一组」场景）。 */
    fun startSession(fresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = StudyUiState.Loading
            if (!fresh) {
                // 只恢复本学习日的快照（凌晨 4 点折算）：跨天的未完成进度直接作废，
                // 已评过分的词调度状态在数据库里，不受影响
                val snapshot = sessionStore.load(DayIndex.today())
                if (snapshot != null && snapshot.hasContent) {
                    restore(snapshot)
                    return@launch
                }
            }

            val config = settingsRepository.queueConfig.first()
            val plan = getStudySession(config)

            reset()
            browseQueue.addAll(plan.newItems)
            plan.newItems.forEach { browseSeen.add(it.wordId) }
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
            plan.newItems.forEach { browseSeen.add(it.wordId) }
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
        // 重建已见集合：队列里的、已入选待背的、以及本轮已评分落库的词，
        // 续拉新词时都不能再抽回来
        browseQueue.forEach { browseSeen.add(it.wordId) }
        selected.forEach { browseSeen.add(it.wordId) }
        newWords.forEach { browseSeen.add(it) }
        selectionTarget = snapshot.selectionTarget
        againCount = snapshot.againCount
        failureCounts.putAll(snapshot.failureCounts)
        snapshot.testQueue.forEachIndexed { index, card ->
            items[card.wordId]?.let { item ->
                // 只有队首（当前卡）允许恢复翻面状态。旧版本可能把「中途卡被翻面」
                // 写进快照（三击撤销时代的残留），直接恢复会让那张卡轮回时摊着答案。
                val flipped = card.flipped && index == 0
                testQueue.add(makeCard(item, card.encounter).copy(flipped = flipped))
            }
        }

        // 词书可能在学习途中被删掉（下架内置词书 / 用户删库）：快照里的词
        // 解析不出来时旧进度作废，按当前词书重新规划，避免空队列 .first() 崩溃
        val phaseViable = when (snapshot.phase) {
            PHASE_BROWSING -> browseQueue.isNotEmpty() || selected.isNotEmpty()
            PHASE_FORMAL, PHASE_BREAK_FORMAL ->
                testQueue.isNotEmpty() || selected.isNotEmpty()
            PHASE_REVIEW, PHASE_BREAK_REVIEW ->
                testQueue.isNotEmpty() || dueItems.isNotEmpty()
            else -> browseQueue.isNotEmpty()
        }
        if (!phaseViable) {
            sessionStore.clear()
            reset()
            val config = settingsRepository.queueConfig.first()
            val plan = getStudySession(config)
            browseQueue.addAll(plan.newItems)
            plan.newItems.forEach { browseSeen.add(it.wordId) }
            dueItems = plan.dueItems
            selectionTarget = plan.selectionTarget
            when {
                plan.isEmpty -> {
                    sessionStore.clear()
                    _uiState.value = StudyUiState.Empty
                }
                browseQueue.isNotEmpty() -> emitBrowse()
                else -> startReviewPhase()
            }
            return
        }

        when (snapshot.phase) {
            PHASE_BROWSING -> if (browseQueue.isNotEmpty()) emitBrowse() else breakTo(BreakKind.START_FORMAL)

            PHASE_FORMAL -> {
                phase = TestPhase.FORMAL
                if (testQueue.isNotEmpty()) emitTest() else startFormalPhase()
            }

            PHASE_REVIEW -> {
                phase = TestPhase.REVIEW
                if (testQueue.isNotEmpty()) emitTest() else startReviewPhase()
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
        browseSeen.clear()
        dueItems = emptyList()
        selectionTarget = 0
        againCount = 0
        failureCounts.clear()
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

    /** 双击卡片外区域撤销上一张卡。提示页和完成页不响应。 */
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
                    // 撤销的是上一张卡：当前这张可能已被翻面看过答案，它仍留在队列里，
                    // 等被撤销的卡重做一轮后还会轮回到它。不复位的话回来直接摊着背面，
                    // 等于开卷考试，所以连同翻面状态一起回滚到正面。
                    testQueue.firstOrNull()?.let { current ->
                        if (current.flipped) testQueue[0] = current.copy(flipped = false)
                    }
                    testQueue.addFirst(entry.card)
                    if (entry.phase == TestPhase.FORMAL) {
                        newWords.remove(entry.wordId)
                    } else {
                        reviewWords.remove(entry.wordId)
                    }
                    if (entry.verdict == Verdict.UNKNOWN) {
                        againCount--
                        // 撤回一次「不会」：计数减一，归零直接摘掉，避免影响
                        // 这个词离队时 GOOD/AGAIN 的判定
                        val left = (failureCounts[entry.wordId] ?: 1) - 1
                        if (left <= 0) failureCounts.remove(entry.wordId)
                        else failureCounts[entry.wordId] = left
                    }
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
        // 攒够目标数量的「不认识」词才进背单词
        if (selected.size >= selectionTarget) {
            breakTo(BreakKind.START_FORMAL)
            return
        }
        if (browseQueue.isNotEmpty()) {
            emitBrowse()
            return
        }
        // 批次抽空但还没选够：先把当前队列进度落快照，再续拉下一批新词
        // （排除已见词，否则上滑入选、还停在 NEW 阶段的词会被重复抽回）。
        // Loading 状态会吃掉所有手势输入，不会并发拉重。
        persist()
        _uiState.value = StudyUiState.Loading
        viewModelScope.launch {
            val config = settingsRepository.queueConfig.first()
            val need = (selectionTarget - selected.size).coerceAtLeast(0)
            val more = getStudySession.fetchMoreNewItems(config, browseSeen.toList(), need)
            more.forEach { browseSeen.add(it.wordId) }
            browseQueue.addAll(more)
            when {
                // 还有词：继续浏览
                browseQueue.isNotEmpty() -> emitBrowse()
                // 词书见底但选到了词：进正式背单词
                selected.isNotEmpty() -> breakTo(BreakKind.START_FORMAL)
                // 一个都没选：直接去复习到期词，不再弹「已选 0 个新词」
                dueItems.isNotEmpty() -> startReviewPhase()
                // 新词、复习都没有：收尾
                else -> finish()
            }
        }
    }

    private fun emitBrowse(replay: SwipeDirection? = null) {
        cardShownAt = timeProvider.now()
        _uiState.value = StudyUiState.Browsing(
            card = BrowseCard(browseQueue.first()),
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
        val elapsed = elapsed()

        // 每个词每次会话只在「第三遍拼写通过、词要离队」时落一次库。
        // 前两遍的「会」和任何时候的「不会」只在内存队列里流转，绝不写库——
        // 以前每遍都提交，三次 GOOD 在几分钟内连走完「1 小时步→毕业」，
        // 学完 1 小时后的复习永远不会出现。
        // 复习阶段分两条路：
        //   快速路（本会话还没答错过）：只考一遍英文面，答会直接出队落 GOOD；
        //   强化路（已经答错过至少一次）：走完整三轮（英→中→拼写），三轮全对才出队，
        //               因 failureCounts>0 落 AGAIN 打回 1 小时重记。
        // 正式阶段（新词）始终走三轮。failedBefore 在更新 failureCounts 之前捕获，
        // 表示「答这一遍之前」是否已经失败过——KNOWN 不更新计数，所以对 KNOWN 判定准确。
        val failedBefore = (failureCounts[item.wordId] ?: 0) > 0
        if (verdict == Verdict.UNKNOWN) {
            failureCounts[item.wordId] = (failureCounts[item.wordId] ?: 0) + 1
        }
        val isFinalPass = when {
            verdict == Verdict.UNKNOWN -> false
            // 复习快速路：答会即出队，不进三轮
            phase == TestPhase.REVIEW && !failedBefore -> true
            // 新词 & 复习强化路：刷满三遍才出队
            else -> card.encounter >= KIND_CYCLE.size
        }
        val grade: Grade? = when {
            !isFinalPass -> null
            // 正式阶段都是新词：会话内卡壳不扣 ease，统一 GOOD，排到 1 小时后
            phase == TestPhase.FORMAL -> Grade.GOOD
            // 复习阶段：中途答错过一次就按 AGAIN 打回 1 小时重记，否则 GOOD 进阶
            (failureCounts[item.wordId] ?: 0) > 0 -> Grade.AGAIN
            else -> Grade.GOOD
        }

        busy = true
        viewModelScope.launch {
            if (grade != null) submitReview(item, grade, elapsed)
            if (phase == TestPhase.FORMAL) newWords.add(item.wordId) else reviewWords.add(item.wordId)
            if (verdict == Verdict.UNKNOWN) againCount++

            var requeuedUid: Long? = null
            // 出队（isFinalPass）就不再重排；否则答会沉底排下一遍、答不会归零回第一遍。
            // 复习快速路答会时 isFinalPass=true，直接出队，不进三轮。
            val nextPass: Int? = when {
                isFinalPass -> null
                verdict == Verdict.UNKNOWN -> FIRST_PASS
                card.encounter < KIND_CYCLE.size -> card.encounter + 1
                else -> null
            }
            nextPass?.let { pass ->
                // 能重排说明词还没离队，这一下必然没落库，状态保持原样
                val requeue = makeCard(item, pass)
                requeuedUid = requeue.uid
                testQueue.addLast(requeue)
            }

            // 撤销时卡片要回到它刚出现的样子：停在正面，而不是评分时翻开的背面。
            // stateBefore 为 null 表示这一下压根没写库，撤销时也不碰数据库。
            undoStack.add(
                UndoEntry.Test(
                    item.wordId,
                    if (grade != null) item.state else null,
                    direction,
                    verdict,
                    phase,
                    card.copy(flipped = false),
                    requeuedUid
                )
            )
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
                // 和 load 的判定同一把尺子（凌晨 4 点折算），否则夜里 0-4 点
                // 存的快照会被误判成隔天而丢掉
                day = DayIndex.today(),
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
                failureCounts = HashMap(failureCounts),
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
