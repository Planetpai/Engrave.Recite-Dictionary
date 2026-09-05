package com.example.vocabwatch.domain.srs

import com.example.vocabwatch.domain.model.Grade
import com.example.vocabwatch.domain.model.LearningStage
import com.example.vocabwatch.domain.model.ReviewState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * SM-2 的可调参数。默认值参照 Anki 的调度器，比教科书版原始 SM-2
 * 多了学习步骤和 Hard 惩罚，后者对新词当天记忆更友好。
 */
data class Sm2Params(
    /** 新词的初始 ease factor */
    val initialEase: Double = 2.5,
    /** ease factor 下限，防止间隔永远涨不起来 */
    val minimumEase: Double = 1.3,
    /** 新词当天反复记的间隔（分钟）。答对走完最后一步就毕业 */
    val learningStepsMinutes: List<Double> = listOf(1.0, 10.0),
    /** 复习答错后打回来重记的间隔（分钟） */
    val relearningStepsMinutes: List<Double> = listOf(10.0),
    /** 学习阶段答 GOOD 毕业后的间隔（天） */
    val graduatingIntervalDays: Double = 1.0,
    /** 学习阶段答 EASY 直接跳到的间隔（天） */
    val easyIntervalDays: Double = 4.0,
    /** 复习阶段答 HARD 时，间隔按这个倍数增长，而不是按 ease factor */
    val hardIntervalMultiplier: Double = 1.2,
    /** 复习阶段答 HARD / AGAIN 对 ease factor 的惩罚 */
    val hardEasePenalty: Double = 0.15,
    val againEasePenalty: Double = 0.20,
    /** 复习阶段答 EASY 的额外奖励 */
    val easyEaseBonus: Double = 0.15,
    val easyIntervalBonus: Double = 1.3,
    /** 间隔上限，防止算出几十年后的复习 */
    val maximumIntervalDays: Double = 365.0 * 5,
    /** 答错后连续答对次数是否归零 */
    val resetRepetitionsOnLapse: Boolean = true
)

/**
 * SM-2 间隔重复调度器。
 *
 * 原始 SM-2 只有「答对就 interval * EF」一条规则，这里按 Anki 的做法扩展成
 * 三段式：新词先走分钟级学习步骤，毕业后进入天级间隔复习，复习答错再打回
 * 重记阶段。ease factor 仍用 SM-2 原公式更新。
 *
 * 本类是纯函数式的：不碰数据库、不取系统时间，全部输入通过参数传入，
 * 因此可以直接单元测试。
 */
class Sm2Scheduler(
    private val params: Sm2Params = Sm2Params()
) : Scheduler {

    override val name: String = "SM-2"

    override fun schedule(state: ReviewState, grade: Grade, now: Long): ReviewOutcome =
        when (state.stage) {
            LearningStage.NEW -> scheduleLearning(
                state = state.copy(stage = LearningStage.LEARNING, stepIndex = 0),
                grade = grade,
                now = now
            )

            LearningStage.LEARNING, LearningStage.RELEARNING ->
                scheduleLearning(state, grade, now)

            LearningStage.REVIEW -> scheduleReview(state, grade, now)
        }

    // ---------------------------------------------------------------- 学习阶段

    private fun scheduleLearning(state: ReviewState, grade: Grade, now: Long): ReviewOutcome {
        val steps = stepsFor(state.stage)
        val index = state.stepIndex.coerceIn(0, max(0, steps.lastIndex))

        return when (grade) {
            // 完全忘了：回到第一步
            Grade.AGAIN -> {
                val minutes = steps.first()
                learningOutcome(state, stepIndex = 0, minutes = minutes, now = now)
            }

            // 有点印象：在当前步和下一步之间取中，Anki 的处理方式
            Grade.HARD -> {
                val minutes = if (index + 1 <= steps.lastIndex) {
                    (steps[index] + steps[index + 1]) / 2.0
                } else {
                    steps[index] * 1.5
                }
                learningOutcome(state, stepIndex = index, minutes = minutes, now = now)
            }

            // 记住了：前进到下一步，走完最后一步则毕业
            Grade.GOOD -> {
                if (index + 1 <= steps.lastIndex) {
                    learningOutcome(state, stepIndex = index + 1, minutes = steps[index + 1], now = now)
                } else {
                    graduate(state, now, params.graduatingIntervalDays)
                }
            }

            // 很简单：跳过剩余步骤，直接毕业到更长的间隔
            Grade.EASY -> graduate(state, now, params.easyIntervalDays, params.easyEaseBonus)
        }
    }

    private fun stepsFor(stage: LearningStage): List<Double> =
        if (stage == LearningStage.RELEARNING) params.relearningStepsMinutes else params.learningStepsMinutes

    private fun learningOutcome(
        state: ReviewState,
        stepIndex: Int,
        minutes: Double,
        now: Long
    ): ReviewOutcome {
        val dueIn = minutesToMillis(minutes)
        return ReviewOutcome(
            newState = state.copy(
                stage = state.stage,
                stepIndex = stepIndex,
                intervalDays = minutes / MINUTES_PER_DAY,
                dueAt = now + dueIn,
                lastReviewedAt = now
            ),
            scheduledInDays = minutes / MINUTES_PER_DAY,
            graduated = false
        )
    }

    /** 学习阶段结束，进入天级间隔复习。 */
    private fun graduate(
        state: ReviewState,
        now: Long,
        intervalDays: Double,
        easeBonus: Double = 0.0
    ): ReviewOutcome {
        val ease = clampEase(state.easeFactor + easeBonus)
        return ReviewOutcome(
            newState = state.copy(
                stage = LearningStage.REVIEW,
                stepIndex = 0,
                intervalDays = intervalDays,
                easeFactor = ease,
                repetitions = state.repetitions + 1,
                dueAt = now + daysToMillis(intervalDays),
                lastReviewedAt = now
            ),
            scheduledInDays = intervalDays,
            graduated = true
        )
    }

    // ---------------------------------------------------------------- 复习阶段

    private fun scheduleReview(state: ReviewState, grade: Grade, now: Long): ReviewOutcome =
        when (grade) {
            // 忘了：间隔作废，打回重记阶段，lapses 累加
            Grade.AGAIN -> {
                val minutes = params.relearningStepsMinutes.first()
                ReviewOutcome(
                    newState = state.copy(
                        stage = LearningStage.RELEARNING,
                        stepIndex = 0,
                        intervalDays = minutes / MINUTES_PER_DAY,
                        easeFactor = clampEase(state.easeFactor - params.againEasePenalty),
                        repetitions = if (params.resetRepetitionsOnLapse) 0 else state.repetitions,
                        lapses = state.lapses + 1,
                        dueAt = now + minutesToMillis(minutes),
                        lastReviewedAt = now
                    ),
                    scheduledInDays = minutes / MINUTES_PER_DAY,
                    graduated = false
                )
            }

            Grade.HARD -> {
                val ease = clampEase(state.easeFactor - params.hardEasePenalty)
                val interval = clampInterval(state.intervalDays * params.hardIntervalMultiplier)
                reviewOutcome(state, ease, interval, now)
            }

            Grade.GOOD -> {
                val ease = applySm2Ease(state.easeFactor, grade.quality)
                val interval = clampInterval(state.intervalDays * ease)
                reviewOutcome(state, ease, interval, now)
            }

            Grade.EASY -> {
                val ease = clampEase(applySm2Ease(state.easeFactor, grade.quality) + params.easyEaseBonus)
                val interval = clampInterval(state.intervalDays * ease * params.easyIntervalBonus)
                reviewOutcome(state, ease, interval, now)
            }
        }

    private fun reviewOutcome(
        state: ReviewState,
        ease: Double,
        intervalDays: Double,
        now: Long
    ): ReviewOutcome = ReviewOutcome(
        newState = state.copy(
            stage = LearningStage.REVIEW,
            stepIndex = 0,
            intervalDays = intervalDays,
            easeFactor = ease,
            repetitions = state.repetitions + 1,
            dueAt = now + daysToMillis(intervalDays),
            lastReviewedAt = now
        ),
        scheduledInDays = intervalDays,
        graduated = false
    )

    /**
     * SM-2 原式：EF' = EF + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))
     * q=5 加 0.10，q=4 不变，q=3 减 0.14，q=2 减 0.32，q=1 减 0.54。
     */
    private fun applySm2Ease(currentEase: Double, quality: Int): Double {
        val factor = 5 - quality
        val delta = 0.1 - factor * (0.08 + factor * 0.02)
        return clampEase(currentEase + delta)
    }

    private fun clampEase(ease: Double): Double = max(params.minimumEase, ease)

    private fun clampInterval(days: Double): Double =
        min(params.maximumIntervalDays, max(1.0, days))

    private fun daysToMillis(days: Double): Long = (days * MILLIS_PER_DAY).roundToLong()

    private fun minutesToMillis(minutes: Double): Long = (minutes * MILLIS_PER_MINUTE).roundToLong()

    private companion object {
        const val MINUTES_PER_DAY = 1440.0
        const val MILLIS_PER_MINUTE = 60_000L
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
