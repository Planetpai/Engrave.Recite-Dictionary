package com.example.vocabwatch.domain.model

/**
 * 记忆阶段。NEW 是从未学过，LEARNING 是当天反复记的新词，
 * REVIEW 是已经进入长期间隔复习，RELEARNING 是复习时答错被打回来重记。
 */
enum class LearningStage {
    NEW,
    LEARNING,
    REVIEW,
    RELEARNING;

    val isLearningPhase: Boolean
        get() = this == LEARNING || this == RELEARNING
}

/**
 * 某个词条的复习调度状态。这是间隔重复算法的全部输入，
 * 除了 wordId 之外不含任何词条内容。
 */
data class ReviewState(
    val wordId: Long,
    val stage: LearningStage = LearningStage.NEW,
    /** 在 learningSteps 里的下标，只有 LEARNING / RELEARNING 阶段有意义 */
    val stepIndex: Int = 0,
    /** 当前间隔，单位天。学习中是分钟换算来的小数 */
    val intervalDays: Double = 0.0,
    /** SM-2 的 ease factor，越大说明记得越牢，下次间隔涨得越快 */
    val easeFactor: Double = DEFAULT_EASE_FACTOR,
    /** 连续答对次数，答错归零 */
    val repetitions: Int = 0,
    /** 累计答错次数，只增不减 */
    val lapses: Int = 0,
    /** 下次到期的时间戳（epoch millis）。0 表示立即可学 */
    val dueAt: Long = 0L,
    val lastReviewedAt: Long? = null
) {
    val isNew: Boolean get() = stage == LearningStage.NEW

    fun isDueAt(now: Long): Boolean = !isNew && dueAt <= now

    companion object {
        const val DEFAULT_EASE_FACTOR = 2.5

        fun initial(wordId: Long): ReviewState = ReviewState(wordId = wordId)
    }
}

/** 一条复习记录，用于统计正确率、连续天数、反应时长。 */
data class ReviewLog(
    val id: Long = 0,
    val wordId: Long,
    val grade: Grade,
    val reviewedAt: Long,
    val stageBefore: LearningStage,
    val stageAfter: LearningStage,
    val intervalBeforeDays: Double,
    val intervalAfterDays: Double,
    /** 用户在这张卡片上停留了多久，可用于后续分析难度 */
    val elapsedMillis: Long = 0
)
