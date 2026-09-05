package com.example.vocabwatch.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "word_books",
    indices = [Index(value = ["sourceKey"], unique = true)]
)
data class WordBookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    /** 词书来源标识，唯一索引保证同一份资产不会被导入两遍 */
    val sourceKey: String?,
    val wordCount: Int,
    val createdAt: Long
)

@Entity(
    tableName = "words",
    foreignKeys = [
        ForeignKey(
            entity = WordBookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("bookId"),
        Index(value = ["bookId", "term"], unique = true)
    ]
)
data class WordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val term: String,
    val meaning: String,
    val phonetic: String?,
    val example: String?,
    val exampleTranslation: String?,
    /** 词书内的原始顺序，新词按这个顺序出 */
    val position: Int
)

/**
 * 每个词条一行，导入词书时就预建好（stage = NEW），
 * 这样所有复习查询都能用 INNER JOIN，不必处理「还没学过」的 null 情况。
 *
 * stage / grade 以枚举名字符串存储，方便直接用 sqlite3 查看，
 * 也避免 Room 的 TypeConverter 让 SQL 里的字面量比较失效。
 */
@Entity(
    tableName = "review_states",
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["wordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["wordId"], unique = true),
        Index("stage"),
        Index("dueAt")
    ]
)
data class ReviewStateEntity(
    @PrimaryKey val wordId: Long,
    val stage: String,
    val stepIndex: Int,
    val intervalDays: Double,
    val easeFactor: Double,
    val repetitions: Int,
    val lapses: Int,
    val dueAt: Long,
    val lastReviewedAt: Long?
)

@Entity(
    tableName = "review_logs",
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["wordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("wordId"),
        Index("reviewDay"),
        Index("reviewedAt")
    ]
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: Long,
    val grade: String,
    val reviewedAt: Long,
    /**
     * 本地日期的 epochDay，按 DayIndex 的翻天时刻折算。
     * 连续天数和「今日」统计都基于它，避免每次查询都做时区换算。
     */
    val reviewDay: Long,
    val stageBefore: String,
    val stageAfter: String,
    val intervalBeforeDays: Double,
    val intervalAfterDays: Double,
    val elapsedMillis: Long
)
