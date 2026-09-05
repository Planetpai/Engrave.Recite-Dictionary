package com.example.vocabwatch.domain.model

/**
 * 用户对一张卡片的自评。quality 是 SM-2 原始算法的 0-5 评分，
 * 这里只取实际会用到的四档，对应 Anki 的 Again / Hard / Good / Easy。
 *
 * 显示文案属于 UI 层，不放在这里。
 */
enum class Grade(val quality: Int) {
    AGAIN(1),
    HARD(3),
    GOOD(4),
    EASY(5);

    val isPass: Boolean get() = this == GOOD || this == EASY
}

/** 学习队列的约束条件，存在 DataStore 里，用户可调。 */
data class StudyQueueConfig(
    /** 每天最多学多少个新词 */
    val dailyNewLimit: Int = 10,
    /** 每天最多复习多少个到期词 */
    val dailyReviewLimit: Int = 100,
    /** 一次会话最多取多少张卡片，手表上不宜太长 */
    val sessionSize: Int = 20,
    /** 新词和到期词混排时，新词最多占的比例 */
    val newCardShare: Double = 0.4,
    /** 只学这本词书；null 表示全部词书 */
    val activeBookId: Long? = null
) {
    companion object {
        val DEFAULT = StudyQueueConfig()
    }
}

/** 首页/统计页要展示的数据。 */
data class StudyStats(
    val totalWords: Int = 0,
    /** 学过的词数，即 stage 不是 NEW 的 */
    val learnedWords: Int = 0,
    val dueNow: Int = 0,
    val newRemaining: Int = 0,
    val reviewedToday: Int = 0,
    val correctTodayRate: Double? = null,
    /** 连续学习天数，今天没学就按昨天为止算 */
    val streakDays: Int = 0
) {
    val progress: Float
        get() = if (totalWords == 0) 0f else learnedWords.toFloat() / totalWords.toFloat()

    val sessionSize: Int
        get() = dueNow + newRemaining
}

/** 一张待学的卡片：词条内容 + 它的调度状态。 */
data class StudyItem(
    val word: Word,
    val state: ReviewState
) {
    val wordId: Long get() = word.id
    val isNew: Boolean get() = state.isNew
}
