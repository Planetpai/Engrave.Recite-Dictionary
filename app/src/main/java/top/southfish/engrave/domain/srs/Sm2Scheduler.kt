package top.southfish.engrave.domain.srs

import top.southfish.engrave.domain.model.Grade
import top.southfish.engrave.domain.model.LearningStage
import top.southfish.engrave.domain.model.ReviewState
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * SM-2 间隔重复算法的实现（2026-09 改造版）。
 *
 * 核心改动相对原版 SM-2：
 * 1. 天级间隔到期时间锚定到凌晨 4 点，同一天学的卡统一次日 4 点到期
 * 2. 复习阶段先走固定阶梯 [1,2,4,7,15] 天，再转 SM-2 乘法
 * 3. 忘词不重置到 1 天，而是 max(1天, 旧间隔 × 0.5)
 * 4. ease hell 保护：ease < 2.0 时 GOOD 给 +0.10（正常 +0.05）
 * 5. 学习阶段最后一步点 HARD 直接毕业
 * 6. 逾期补偿：晚复习的天数按 0.5 系数加到新间隔
 * 7. fuzz：天级间隔加 ±5% 整数天抖动，10 分钟/1 小时不加
 *
 * 纯函数：所有随机性通过构造时注入的 [random] 控制，方便测试。
 */
class Sm2Scheduler(
    private val params: Sm2Params = Sm2Params.Default,
    private val random: Random = Random,
    private val zone: ZoneId = ZoneId.systemDefault()
) : Scheduler {

    override val name: String = "SM-2-ladder"

    override fun schedule(
        state: ReviewState,
        grade: Grade,
        now: Long
    ): ReviewOutcome = when (state.stage) {
        LearningStage.NEW, LearningStage.LEARNING -> scheduleLearning(state, grade, now)
        LearningStage.RELEARNING -> scheduleRelearning(state, grade, now)
        LearningStage.REVIEW -> scheduleReview(state, grade, now)
    }

    // ---------------------------------------------------------------- 学习阶段
    private fun scheduleLearning(state: ReviewState, grade: Grade, now: Long): ReviewOutcome {
        val steps = params.learningStepsMinutes
        // stepIndex 语义是「当前所在步」：
        //   新词(NEW)还没进任何步；首次 GOOD 先进 steps[0]（10 分钟），
        //   再次 GOOD 才进 steps[1]（1 小时），走完最后一步毕业。
        //   旧实现首次 GOOD 直接跳到 steps[1]，10 分钟步在正常路径上被跳过。
        val isNew = state.stage == LearningStage.NEW
        val stepIndex = state.stepIndex.coerceIn(0, steps.lastIndex)
        val isLastStep = !isNew && stepIndex >= steps.lastIndex

        return when (grade) {
            Grade.AGAIN -> learningOutcome(
                state = state,
                stepIndex = 0,
                minutes = steps[0],
                easeChange = -params.againEasePenalty,
                now = now,
                graduated = false
            )

            Grade.HARD -> {
                if (isLastStep) {
                    // 最后一步点 HARD：直接毕业，间隔打 8 折
                    graduate(state, now, intervalDays = params.reviewIntervalLadderDays[0] * params.hardIntervalMultiplier, rungIndex = 0)
                } else {
                    // NEW 或中间步：停在当前应在步（NEW 视作第 0 步），间隔 ×1.5
                    val stayStep = if (isNew) 0 else stepIndex
                    learningOutcome(
                        state = state,
                        stepIndex = stayStep,
                        minutes = steps[stayStep] * 1.5,
                        easeChange = -params.hardEasePenalty,
                        now = now,
                        graduated = false
                    )
                }
            }

            Grade.GOOD -> {
                if (isLastStep) {
                    // 走完最后一步：毕业到第一档（1 天）
                    graduate(state, now, intervalDays = params.reviewIntervalLadderDays[0], rungIndex = 0)
                } else {
                    val targetStep = if (isNew) 0 else stepIndex + 1
                    learningOutcome(
                        state = state,
                        stepIndex = targetStep,
                        minutes = steps[targetStep],
                        easeChange = goodEaseBonus(state.easeFactor),
                        now = now,
                        graduated = false
                    )
                }
            }

            Grade.EASY -> {
                // EASY 直接毕业，跳到第二档（2 天）
                val rungIndex = minOf(1, params.reviewIntervalLadderDays.lastIndex)
                graduate(
                    state,
                    now,
                    intervalDays = params.reviewIntervalLadderDays[rungIndex],
                    rungIndex = rungIndex,
                    easeChange = params.easyEaseBonus
                )
            }
        }
    }

    // ---------------------------------------------------------------- 重记阶段
    private fun scheduleRelearning(state: ReviewState, grade: Grade, now: Long): ReviewOutcome {
        val steps = params.relearningStepsMinutes
        val stepIndex = state.stepIndex.coerceIn(0, steps.lastIndex)

        return when (grade) {
            Grade.AGAIN -> learningOutcome(
                state = state,
                stepIndex = 0,
                minutes = steps[0],
                easeChange = -params.againEasePenalty,
                now = now,
                graduated = false
            )

            Grade.HARD -> {
                val isLastStep = stepIndex >= steps.lastIndex
                if (isLastStep) {
                    relearningGraduate(state, now)
                } else {
                    learningOutcome(
                        state = state,
                        stepIndex = stepIndex,
                        minutes = steps[stepIndex] * 1.5,
                        easeChange = -params.hardEasePenalty,
                        now = now,
                        graduated = false
                    )
                }
            }

            Grade.GOOD -> {
                val nextStep = stepIndex + 1
                if (nextStep >= steps.size) {
                    relearningGraduate(state, now)
                } else {
                    learningOutcome(
                        state = state,
                        stepIndex = nextStep,
                        minutes = steps[nextStep],
                        easeChange = goodEaseBonus(state.easeFactor),
                        now = now,
                        graduated = false
                    )
                }
            }

            Grade.EASY -> relearningGraduate(state, now, easeChange = params.easyEaseBonus)
        }
    }

    /**
     * 重记毕业：新间隔 = max(1天, 旧间隔 × 0.5)，回到对应阶梯档。
     * 旧间隔 < 1 天时（比如刚毕业就忘），直接给 1 天。
     * stepIndex 按恢复后的间隔对齐到阶梯档，避免下次 GOOD 错误地跳回 2 天。
     */
    private fun relearningGraduate(
        state: ReviewState,
        now: Long,
        easeChange: Double = 0.0
    ): ReviewOutcome {
        val recovered = max(params.minimumLapseIntervalDays, state.intervalDays * params.lapseIntervalMultiplier)
        val newEase = clampEase(state.easeFactor + easeChange)
        val intervalDays = min(recovered, params.maximumIntervalDays)
        val rungIndex = rungForInterval(intervalDays)
        val dueAt = dayAnchoredDueAt(intervalDays, now)
        return ReviewOutcome(
            newState = state.copy(
                stage = LearningStage.REVIEW,
                stepIndex = rungIndex,
                intervalDays = intervalDays,
                easeFactor = newEase,
                repetitions = if (params.resetRepetitionsOnLapse) 0 else state.repetitions,
                dueAt = dueAt
            ),
            scheduledInDays = intervalDays,
            graduated = true
        )
    }

    /** 根据间隔天数反查阶梯档：取最大的 ladder[i] <= interval；超出阶梯则返回 ladder.size（SM-2 区）。 */
    private fun rungForInterval(intervalDays: Double): Int {
        val ladder = params.reviewIntervalLadderDays
        for (i in ladder.indices) {
            if (intervalDays < ladder[i]) return (i - 1).coerceAtLeast(0)
        }
        return ladder.size
    }

    // ---------------------------------------------------------------- 复习阶段
    private fun scheduleReview(state: ReviewState, grade: Grade, now: Long): ReviewOutcome {
        return when (grade) {
            Grade.AGAIN -> {
                // 打回重记，ease 扣 0.20。重记毕业后的间隔在 relearningGraduate 里算。
                val newEase = clampEase(state.easeFactor - params.againEasePenalty)
                val minutes = params.relearningStepsMinutes[0]
                ReviewOutcome(
                    newState = state.copy(
                        stage = LearningStage.RELEARNING,
                        stepIndex = 0,
                        easeFactor = newEase,
                        repetitions = if (params.resetRepetitionsOnLapse) 0 else state.repetitions,
                        lapses = state.lapses + 1,
                        dueAt = now + (minutes * MINUTES_TO_MILLIS).toLong()
                    ),
                    scheduledInDays = minutes / MINUTES_PER_DAY,
                    graduated = false
                )
            }

            Grade.HARD -> {
                // 留在当前档，间隔 ×0.8，ease −0.15
                val newEase = clampEase(state.easeFactor - params.hardEasePenalty)
                val baseInterval = currentIntervalForRung(state.stepIndex, state.intervalDays)
                val intervalDays = max(1.0, min(baseInterval * params.hardIntervalMultiplier, params.maximumIntervalDays))
                val dueAt = dayAnchoredDueAt(intervalDays, now)
                ReviewOutcome(
                    newState = state.copy(
                        easeFactor = newEase,
                        intervalDays = intervalDays,
                        dueAt = dueAt
                    ),
                    scheduledInDays = intervalDays,
                    graduated = false
                )
            }

            Grade.GOOD -> {
                val newEase = clampEase(state.easeFactor + goodEaseBonus(state.easeFactor))
                val nextRung = state.stepIndex + 1
                val intervalDays = computeReviewInterval(nextRung, state.intervalDays, newEase, now, state.dueAt)
                val dueAt = dayAnchoredDueAt(intervalDays, now)
                ReviewOutcome(
                    newState = state.copy(
                        stepIndex = nextRung,
                        easeFactor = newEase,
                        intervalDays = intervalDays,
                        repetitions = state.repetitions + 1,
                        dueAt = dueAt
                    ),
                    scheduledInDays = intervalDays,
                    graduated = false
                )
            }

            Grade.EASY -> {
                val newEase = clampEase(state.easeFactor + params.easyEaseBonus)
                val nextRung = state.stepIndex + params.easyRungJump
                val intervalDays = computeReviewInterval(nextRung, state.intervalDays, newEase, now, state.dueAt)
                val dueAt = dayAnchoredDueAt(intervalDays, now)
                ReviewOutcome(
                    newState = state.copy(
                        stepIndex = nextRung,
                        easeFactor = newEase,
                        intervalDays = intervalDays,
                        repetitions = state.repetitions + 1,
                        dueAt = dueAt
                    ),
                    scheduledInDays = intervalDays,
                    graduated = false
                )
            }
        }
    }

    // ---------------------------------------------------------------- 工具函数

    /**
     * 根据阶梯档位算下次间隔。
     * - 档位在阶梯内 → 用阶梯值
     * - 超出阶梯 → SM-2 乘法：旧间隔 × ease
     * - 同时叠加逾期补偿
     */
    private fun computeReviewInterval(
        rungIndex: Int,
        prevIntervalDays: Double,
        ease: Double,
        now: Long,
        prevDueAt: Long
    ): Double {
        val base = if (rungIndex < params.reviewIntervalLadderDays.size) {
            params.reviewIntervalLadderDays[rungIndex]
        } else {
            prevIntervalDays * ease
        }
        val withOverdue = applyOverdueCompensation(base, prevIntervalDays, now, prevDueAt)
        return max(1.0, min(withOverdue, params.maximumIntervalDays))
    }

    /** HARD 时不换档，用当前档位对应的间隔。 */
    private fun currentIntervalForRung(rungIndex: Int, prevIntervalDays: Double): Double =
        if (rungIndex < params.reviewIntervalLadderDays.size) {
            params.reviewIntervalLadderDays[rungIndex]
        } else {
            prevIntervalDays
        }

    /**
     * 逾期补偿：新间隔 += 逾期天数 × 0.5，上限 = 原间隔 × 2。
     * 逾期天数按 4AM 日界对齐计算，避免时区/小时误差。
     */
    private fun applyOverdueCompensation(
        baseInterval: Double,
        prevIntervalDays: Double,
        now: Long,
        prevDueAt: Long
    ): Double {
        val overdueDays = overdueDays(now, prevDueAt)
        if (overdueDays <= 0) return baseInterval
        val bonus = overdueDays * params.overdueCompensationFactor
        val cap = prevIntervalDays * params.overdueCompensationMaxMultiplier
        return baseInterval + min(bonus, cap)
    }

    /** 逾期天数：(now 所在学习日) - (dueAt 所在学习日)。<=0 表示没逾期。 */
    private fun overdueDays(now: Long, dueAt: Long): Long {
        if (dueAt <= 0) return 0
        val nowDay = studyDay(now)
        val dueDay = studyDay(dueAt)
        return (nowDay - dueDay).coerceAtLeast(0)
    }

    /**
     * 计算天级间隔的到期时间：锚定到「下一个凌晨 4 点」+ ceil(间隔天数 - 1) 天 + fuzz。
     * 用 ceil 是因为 SM-2 乘法会产生小数间隔（如 38.25 天），直接乘毫秒会让
     * 到期时间偏离 4AM；向上取整保证到期时刻始终精确在 4:00:00。
     * 10 分钟/1 小时不走这里（由调用方直接 now + 毫秒）。
     */
    private fun dayAnchoredDueAt(intervalDays: Double, now: Long): Long {
        val offsetDays = kotlin.math.ceil(intervalDays - 1.0).toLong()
        val base = nextDayBoundary(now) + offsetDays * MILLIS_PER_DAY
        val fuzzed = base + fuzzDays(intervalDays) * MILLIS_PER_DAY
        // fuzz 是整数天，所以到期时间仍精确在 4:00:00，不会落入 ±1 小时窗口
        return fuzzed
    }

    /**
     * fuzz：±5% 的整数天。间隔 < 20 天时 5% 不足 1 天，fuzz 为 0。
     * 10 分钟/1 小时的调用方不会走到这里，所以天然不加抖动。
     */
    private fun fuzzDays(intervalDays: Double): Long {
        val magnitude = (intervalDays * params.fuzzRatio).toLong()
        if (magnitude <= 0) return 0L
        val sign = if (random.nextBoolean()) 1 else -1
        return sign * magnitude
    }

    /** 下一个凌晨 4 点的时间戳。如果现在就在 4 点整或刚过，返回明天的 4 点。 */
    private fun nextDayBoundary(now: Long): Long {
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val today4am = ldt.toLocalDate().atTime(DAY_BOUNDARY_HOUR, 0)
        return if (ldt.isBefore(today4am)) {
            today4am.atZone(zone).toInstant().toEpochMilli()
        } else {
            today4am.plusDays(1).atZone(zone).toInstant().toEpochMilli()
        }
    }

    /** 把时间戳折算成学习日序号（凌晨 4 点为界），用于逾期计算。 */
    private fun studyDay(epochMillis: Long): Long {
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        val shifted = if (ldt.hour < DAY_BOUNDARY_HOUR) ldt.minusDays(1) else ldt
        return shifted.toLocalDate().toEpochDay()
    }

    /** ease < 2.0 时 GOOD 给 +0.10，加速爬出 ease hell。 */
    private fun goodEaseBonus(ease: Double): Double =
        if (ease < params.lowEaseThreshold) params.goodEaseBonusLowEase else params.goodEaseBonus

    private fun clampEase(ease: Double): Double = ease.coerceIn(params.minimumEase, 5.0)

    /** 学习/重记阶段的通用产出（分钟级，精确时间，不锚定 4AM）。 */
    private fun learningOutcome(
        state: ReviewState,
        stepIndex: Int,
        minutes: Double,
        easeChange: Double,
        now: Long,
        graduated: Boolean
    ): ReviewOutcome {
        val newEase = clampEase(state.easeFactor + easeChange)
        val dueAt = now + (minutes * MINUTES_TO_MILLIS).toLong()
        return ReviewOutcome(
            newState = state.copy(
                // NEW 词首次评分后进入 LEARNING 阶段
                stage = if (state.stage == LearningStage.NEW) LearningStage.LEARNING else state.stage,
                stepIndex = stepIndex,
                easeFactor = newEase,
                dueAt = dueAt
            ),
            scheduledInDays = minutes / MINUTES_PER_DAY,
            graduated = graduated
        )
    }

    /** 学习阶段毕业：进入 REVIEW，设定阶梯档位和间隔。 */
    private fun graduate(
        state: ReviewState,
        now: Long,
        intervalDays: Double,
        rungIndex: Int,
        easeChange: Double = 0.0
    ): ReviewOutcome {
        val newEase = clampEase(state.easeFactor + easeChange)
        val clamped = max(1.0, min(intervalDays, params.maximumIntervalDays))
        val dueAt = dayAnchoredDueAt(clamped, now)
        return ReviewOutcome(
            newState = state.copy(
                stage = LearningStage.REVIEW,
                stepIndex = rungIndex,
                intervalDays = clamped,
                easeFactor = newEase,
                repetitions = state.repetitions + 1,
                dueAt = dueAt
            ),
            scheduledInDays = clamped,
            graduated = true
        )
    }

    private companion object {
        const val MINUTES_TO_MILLIS = 60_000L
        const val MINUTES_PER_DAY = 1440.0
        const val MILLIS_PER_DAY = 86_400_000L
        const val DAY_BOUNDARY_HOUR = 4
    }
}
