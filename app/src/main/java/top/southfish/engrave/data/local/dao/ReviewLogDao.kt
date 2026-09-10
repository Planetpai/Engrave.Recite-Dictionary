package top.southfish.engrave.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import top.southfish.engrave.data.local.TodaySummary
import top.southfish.engrave.data.local.entity.ReviewLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewLogDao {

    @Insert
    suspend fun insert(log: ReviewLogEntity): Long

    /**
     * 按学习日聚合，而不是按时间戳范围。
     * reviewDay 在写入时就已经折算好，查询侧不用再关心时区和翻天时刻。
     */
    @Query(
        """
        SELECT
            COUNT(*) AS reviewedToday,
            COALESCE(SUM(CASE WHEN l.grade IN ('GOOD', 'EASY') THEN 1 ELSE 0 END), 0) AS passedToday,
            COALESCE(SUM(CASE WHEN l.stageBefore = 'NEW' THEN 1 ELSE 0 END), 0) AS newLearnedToday
        FROM review_logs AS l
        INNER JOIN words AS w ON w.id = l.wordId
        WHERE l.reviewDay = :day
          AND (:allBooks = 1 OR w.bookId IN (:bookIds))
        """
    )
    fun observeTodaySummary(day: Long, allBooks: Boolean, bookIds: List<Long>): Flow<TodaySummary>

    @Query(
        """
        SELECT COUNT(DISTINCT l.wordId) FROM review_logs AS l
        INNER JOIN words AS w ON w.id = l.wordId
        WHERE l.reviewDay = :day AND l.stageBefore = 'NEW'
          AND l.grade != 'EASY'
          AND (:allBooks = 1 OR w.bookId IN (:bookIds))
        """
    )
    suspend fun countNewLearnedOnDay(day: Long, allBooks: Boolean, bookIds: List<Long>): Int

    @Query(
        """
        SELECT COUNT(*) FROM review_logs AS l
        INNER JOIN words AS w ON w.id = l.wordId
        WHERE l.reviewDay = :day AND (:allBooks = 1 OR w.bookId IN (:bookIds))
        """
    )
    suspend fun countReviewsOnDay(day: Long, allBooks: Boolean, bookIds: List<Long>): Int

    /** 全部有学习记录的日子，用于算连续天数。不分词书——打卡是全局的。 */
    @Query("SELECT DISTINCT reviewDay FROM review_logs")
    fun observeDistinctDays(): Flow<List<Long>>

    @Query("SELECT * FROM review_logs WHERE wordId = :wordId ORDER BY reviewedAt DESC LIMIT :limit")
    suspend fun historyForWord(wordId: Long, limit: Int): List<ReviewLogEntity>

    /**
     * 删掉某个词最新的一条复习日志。
     * 会话内撤销时配合「恢复旧调度状态」一起用，保证统计不会把已撤销的
     * 那次评分算进去。同 reviewedAt 时取 id 更大的，避免并列时删错。
     */
    @Query(
        """
        DELETE FROM review_logs
        WHERE id = (
            SELECT id FROM review_logs
            WHERE wordId = :wordId
            ORDER BY reviewedAt DESC, id DESC
            LIMIT 1
        )
        """
    )
    suspend fun deleteLatestForWord(wordId: Long)
}
