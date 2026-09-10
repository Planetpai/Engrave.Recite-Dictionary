package top.southfish.engrave.data.local

import androidx.room.Embedded
import top.southfish.engrave.data.local.entity.ReviewStateEntity

/**
 * 到期卡 / 新词的查询结果：调度状态 + 展示所需的词条字段。
 *
 * 不用 @Relation 而是直接 JOIN 出扁平的一行，因为学习队列是高频查询，
 * 少一次子查询在手表上更明显。
 */
data class StudyItemRow(
    @Embedded val state: ReviewStateEntity,
    val bookId: Long,
    val term: String,
    val meaning: String,
    val phonetic: String?,
    val example: String?,
    val exampleTranslation: String?,
    val position: Int
)

/** 一次聚合查询拿齐首页要的四个计数，避免四次往返。 */
data class StageCounts(
    val total: Int,
    val learned: Int,
    val dueNow: Int,
    val newRemaining: Int
)

/** 今日复习概况。passed 用于算正确率，newLearned 用于落实每日新词上限。 */
data class TodaySummary(
    val reviewedToday: Int,
    val passedToday: Int,
    val newLearnedToday: Int
)

/** 词书内容占用估算，词书管理页展示。 */
data class BookSize(
    val bookId: Long,
    val bytes: Long
)

/** 导出全量调度状态用：状态 + 它所属的词头。 */
data class StateWithTerm(
    @Embedded val state: ReviewStateEntity,
    val term: String
)
