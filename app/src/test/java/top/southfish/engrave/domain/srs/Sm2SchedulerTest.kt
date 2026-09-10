package top.southfish.engrave.domain.srs

import top.southfish.engrave.domain.model.Grade
import top.southfish.engrave.domain.model.LearningStage
import top.southfish.engrave.domain.model.ReviewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SM-2 调度器的行为测试。
 *
 * 期望值都是按 Sm2Params 的默认参数手算出来的，测试的意义在于锁住这些数字：
 * 以后调参数或换算法时，这里会立刻指出哪一档评分的行为变了。
 */
class Sm2SchedulerTest {

    private val scheduler = Sm2Scheduler()

    /** 固定基准时间，避开 System.currentTimeMillis 带来的不确定性 */
    private val now = 1_700_000_000_000L

    private val minute = 60_000L
    private val day = 86_400_000L

    private fun newState() = ReviewState.initial(wordId = 1L)

    private fun reviewState(
        intervalDays: Double,
        ease: Double = ReviewState.DEFAULT_EASE_FACTOR,
        repetitions: Int = 3,
        lapses: Int = 0
    ) = ReviewState(
        wordId = 1L,
        stage = LearningStage.REVIEW,
        stepIndex = 0,
        intervalDays = intervalDays,
        easeFactor = ease,
        repetitions = repetitions,
        lapses = lapses,
        dueAt = now,
        lastReviewedAt = now - day
    )

    // ------------------------------------------------------------- 新词

    @Test
    fun `新词答记得 - 进入学习阶段第二步 10 分钟后`() {
        val outcome = scheduler.schedule(newState(), Grade.GOOD, now)

        assertEquals(LearningStage.LEARNING, outcome.newState.stage)
        assertEquals(1, outcome.newState.stepIndex)
        assertEquals(now + 10 * minute, outcome.newState.dueAt)
        assertEquals(10.0 / 1440, outcome.scheduledInDays, 1e-9)
        assertTrue(!outcome.graduated)
        assertEquals(now, outcome.newState.lastReviewedAt)
    }

    @Test
    fun `新词答忘了 - 停在学习第一步 1 分钟后`() {
        val outcome = scheduler.schedule(newState(), Grade.AGAIN, now)

        assertEquals(LearningStage.LEARNING, outcome.newState.stage)
        assertEquals(0, outcome.newState.stepIndex)
        assertEquals(now + 1 * minute, outcome.newState.dueAt)
    }

    @Test
    fun `新词答模糊 - 在当前步和下一步之间取中`() {
        val outcome = scheduler.schedule(newState(), Grade.HARD, now)

        // steps = [1, 10]，index 0 → (1 + 10) / 2 = 5.5 分钟 = 330000 毫秒
        assertEquals(0, outcome.newState.stepIndex)
        assertEquals(now + 330_000L, outcome.newState.dueAt)
    }

    @Test
    fun `新词答简单 - 跳过学习步骤直接毕业到 4 天`() {
        val outcome = scheduler.schedule(newState(), Grade.EASY, now)

        assertEquals(LearningStage.REVIEW, outcome.newState.stage)
        assertEquals(4.0, outcome.newState.intervalDays, 1e-9)
        assertEquals(now + 4 * day, outcome.newState.dueAt)
        assertTrue(outcome.graduated)
        // 2.5 + easyEaseBonus 0.15
        assertEquals(2.65, outcome.newState.easeFactor, 1e-9)
        assertEquals(1, outcome.newState.repetitions)
    }

    // ------------------------------------------------------------- 学习阶段

    @Test
    fun `学习阶段最后一步答记得 - 毕业到 1 天`() {
        val learning = ReviewState(
            wordId = 1L,
            stage = LearningStage.LEARNING,
            stepIndex = 1,
            intervalDays = 10.0 / 1440,
            dueAt = now
        )

        val outcome = scheduler.schedule(learning, Grade.GOOD, now)

        assertEquals(LearningStage.REVIEW, outcome.newState.stage)
        assertEquals(1.0, outcome.newState.intervalDays, 1e-9)
        assertEquals(now + day, outcome.newState.dueAt)
        assertTrue(outcome.graduated)
        assertEquals(0, outcome.newState.stepIndex)
    }

    @Test
    fun `学习阶段答忘了 - 退回第一步而不是毕业`() {
        val learning = ReviewState(
            wordId = 1L,
            stage = LearningStage.LEARNING,
            stepIndex = 1,
            dueAt = now
        )

        val outcome = scheduler.schedule(learning, Grade.AGAIN, now)

        assertEquals(LearningStage.LEARNING, outcome.newState.stage)
        assertEquals(0, outcome.newState.stepIndex)
        assertEquals(now + minute, outcome.newState.dueAt)
    }

    // ------------------------------------------------------------- 复习阶段

    @Test
    fun `复习答记得 - 间隔乘以 ease 且 q=4 时 ease 不变`() {
        val outcome = scheduler.schedule(reviewState(intervalDays = 1.0), Grade.GOOD, now)

        // SM-2：q=4 时 delta = 0.1 - 1*(0.08 + 0.02) = 0
        assertEquals(2.5, outcome.newState.easeFactor, 1e-9)
        assertEquals(2.5, outcome.newState.intervalDays, 1e-9)
        assertEquals(now + (2.5 * day).toLong(), outcome.newState.dueAt)
        assertEquals(4, outcome.newState.repetitions)
        assertEquals(0, outcome.newState.lapses)
        assertTrue(!outcome.graduated)
    }

    @Test
    fun `复习答忘了 - 打回重记阶段 且 lapses 加一 且连续次数归零`() {
        val outcome = scheduler.schedule(
            reviewState(intervalDays = 10.0, ease = 2.5, repetitions = 5, lapses = 1),
            Grade.AGAIN,
            now
        )

        assertEquals(LearningStage.RELEARNING, outcome.newState.stage)
        assertEquals(2, outcome.newState.lapses)
        assertEquals(0, outcome.newState.repetitions)
        // ease 2.5 - againEasePenalty 0.20
        assertEquals(2.3, outcome.newState.easeFactor, 1e-9)
        assertEquals(now + 10 * minute, outcome.newState.dueAt)
        // 间隔作废，退回到分钟级
        assertTrue(outcome.newState.intervalDays < 1.0)
    }

    @Test
    fun `复习答模糊 - 间隔按 1_2 倍增长而不是按 ease`() {
        val outcome = scheduler.schedule(reviewState(intervalDays = 10.0, ease = 2.5), Grade.HARD, now)

        assertEquals(12.0, outcome.newState.intervalDays, 1e-9)
        // ease 2.5 - hardEasePenalty 0.15
        assertEquals(2.35, outcome.newState.easeFactor, 1e-9)
    }

    @Test
    fun `复习答简单 - 间隔跳得最多 且 ease 有额外奖励`() {
        val outcome = scheduler.schedule(reviewState(intervalDays = 10.0, ease = 2.5), Grade.EASY, now)

        // ease: q=5 → +0.10，再 + easyEaseBonus 0.15 = 2.75
        assertEquals(2.75, outcome.newState.easeFactor, 1e-9)
        // interval: 10 * 2.75 * easyIntervalBonus 1.3 = 35.75
        assertEquals(35.75, outcome.newState.intervalDays, 1e-9)
    }

    // ------------------------------------------------------------- 边界

    @Test
    fun `ease 不会跌破下限 1_3`() {
        val outcome = scheduler.schedule(reviewState(intervalDays = 5.0, ease = 1.35), Grade.AGAIN, now)

        // 1.35 - 0.20 = 1.15，应被夹到 minimumEase
        assertEquals(1.3, outcome.newState.easeFactor, 1e-9)
    }

    @Test
    fun `间隔有上限 不会算出几十年后`() {
        val params = Sm2Params(maximumIntervalDays = 365.0)
        val capped = Sm2Scheduler(params)

        val outcome = capped.schedule(reviewState(intervalDays = 300.0, ease = 2.5), Grade.EASY, now)

        assertEquals(365.0, outcome.newState.intervalDays, 1e-9)
    }

    @Test
    fun `间隔至少 1 天 不会出现 0_5 天这种复习阶段的值`() {
        // 手动构造一个间隔被压得很低的复习状态
        val outcome = scheduler.schedule(reviewState(intervalDays = 0.2, ease = 1.3), Grade.HARD, now)

        assertTrue(outcome.newState.intervalDays >= 1.0)
    }

    @Test
    fun `连续答记得 - 间隔单调增长`() {
        var state = newState()
        val intervals = mutableListOf<Double>()

        // 先走完学习阶段
        state = scheduler.schedule(state, Grade.GOOD, now).newState
        state = scheduler.schedule(state, Grade.GOOD, now).newState
        intervals += state.intervalDays

        repeat(5) {
            val outcome = scheduler.schedule(state, Grade.GOOD, now)
            intervals += outcome.newState.intervalDays
            state = outcome.newState
        }

        for (i in 1 until intervals.size) {
            assertTrue(
                "间隔应当单调增长，但第 $i 步从 ${intervals[i - 1]} 变成了 ${intervals[i]}",
                intervals[i] > intervals[i - 1]
            )
        }
    }

    @Test
    fun `调度器是纯函数 - 同样输入得到同样输出`() {
        val state = reviewState(intervalDays = 6.0, ease = 2.4)

        val first = scheduler.schedule(state, Grade.GOOD, now)
        val second = scheduler.schedule(state, Grade.GOOD, now)

        assertEquals(first, second)
        // 输入状态没有被就地修改
        assertEquals(6.0, state.intervalDays, 1e-9)
        assertEquals(2.4, state.easeFactor, 1e-9)
    }

    @Test
    fun `重记阶段答记得 - 走完重记步骤后回到复习阶段`() {
        val relearning = ReviewState(
            wordId = 1L,
            stage = LearningStage.RELEARNING,
            stepIndex = 0,
            intervalDays = 10.0 / 1440,
            easeFactor = 2.3,
            lapses = 1,
            dueAt = now
        )

        // relearningSteps 只有一步 [10]，所以 index 0 就是最后一步 → 毕业
        val outcome = scheduler.schedule(relearning, Grade.GOOD, now)

        assertEquals(LearningStage.REVIEW, outcome.newState.stage)
        assertEquals(1.0, outcome.newState.intervalDays, 1e-9)
        assertTrue(outcome.graduated)
        assertEquals(1, outcome.newState.lapses)
    }
}
