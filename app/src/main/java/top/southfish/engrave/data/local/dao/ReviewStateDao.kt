package top.southfish.engrave.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import top.southfish.engrave.data.local.StateWithTerm
import top.southfish.engrave.data.local.StageCounts
import top.southfish.engrave.data.local.StudyItemRow
import top.southfish.engrave.data.local.entity.ReviewStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewStateDao {

    @Query("SELECT * FROM review_states WHERE wordId = :wordId")
    suspend fun getByWordId(wordId: Long): ReviewStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ReviewStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<ReviewStateEntity>)

    /**
     * 到期需要复习的卡片，最逾期的排前面。
     *
     * stage != 'NEW' 排除了还没学过的词——那些走 getNewItems，
     * 两者的每日配额是分开算的。
     */
    @Query(
        """
        SELECT rs.*, w.bookId AS bookId, w.term AS term, w.meaning AS meaning,
               w.phonetic AS phonetic, w.example AS example,
               w.exampleTranslation AS exampleTranslation, w.position AS position
        FROM review_states AS rs
        INNER JOIN words AS w ON w.id = rs.wordId
        WHERE rs.stage != 'NEW' AND rs.dueAt <= :now
          AND (:allBooks = 1 OR w.bookId IN (:bookIds))
        ORDER BY rs.dueAt ASC
        LIMIT :limit
        """
    )
    suspend fun getDueItems(
        allBooks: Boolean,
        bookIds: List<Long>,
        now: Long,
        limit: Int
    ): List<StudyItemRow>

    /**
     * 还没学过的新词，按词书内的原始顺序出。
     *
     * [excludeIds] 排除本会话已经见过的词：浏览时上滑「不认识」的词并不写库，
     * 阶段仍是 NEW，续拉下一批时不排除就会被重复抽回来。空列表不过滤。
     */
    @Query(
        """
        SELECT rs.*, w.bookId AS bookId, w.term AS term, w.meaning AS meaning,
               w.phonetic AS phonetic, w.example AS example,
               w.exampleTranslation AS exampleTranslation, w.position AS position
        FROM review_states AS rs
        INNER JOIN words AS w ON w.id = rs.wordId
        WHERE rs.stage = 'NEW'
          AND (:allBooks = 1 OR w.bookId IN (:bookIds))
          AND w.id NOT IN (:excludeIds)
        ORDER BY w.bookId ASC, w.position ASC, w.id ASC
        LIMIT :limit
        """
    )
    suspend fun getNewItems(
        allBooks: Boolean,
        bookIds: List<Long>,
        excludeIds: List<Long>,
        limit: Int
    ): List<StudyItemRow>

    /** 按词 id 批量取学习项，恢复会话快照用。顺序不保证，调用方自己排。 */
    @Query(
        """
        SELECT rs.*, w.bookId AS bookId, w.term AS term, w.meaning AS meaning,
               w.phonetic AS phonetic, w.example AS example,
               w.exampleTranslation AS exampleTranslation, w.position AS position
        FROM review_states AS rs
        INNER JOIN words AS w ON w.id = rs.wordId
        WHERE rs.wordId IN (:wordIds)
        """
    )
    suspend fun getItemsByIds(wordIds: List<Long>): List<StudyItemRow>

    /** 首页四个计数一次查完。SUM 在无行时返回 NULL，所以套 COALESCE。 */
    @Query(
        """
        SELECT
            COUNT(*) AS total,
            COALESCE(SUM(CASE WHEN rs.stage != 'NEW' THEN 1 ELSE 0 END), 0) AS learned,
            COALESCE(SUM(CASE WHEN rs.stage != 'NEW' AND rs.dueAt <= :now THEN 1 ELSE 0 END), 0) AS dueNow,
            COALESCE(SUM(CASE WHEN rs.stage = 'NEW' THEN 1 ELSE 0 END), 0) AS newRemaining
        FROM review_states AS rs
        INNER JOIN words AS w ON w.id = rs.wordId
        WHERE (:allBooks = 1 OR w.bookId IN (:bookIds))
        """
    )
    fun observeStageCounts(allBooks: Boolean, bookIds: List<Long>, now: Long): Flow<StageCounts>

    @Query("SELECT COUNT(*) FROM review_states WHERE stage = 'NEW'")
    suspend fun countNew(): Int

    /**
     * 跨日逾期的学习卡自动毕业（学习会话开始时调用）。
     *
     * 学习阶段的 10 分钟/1 小时步骤是为「当天会话内反复记」设计的。如果一张卡
     * 的 dueAt 已经早于当前学习日起点（凌晨 4 点），说明隔了一夜没复习，再让用户
     * 补走分钟级步骤只会把毕业时间推到第二天（表现为「学完第二天没有复习」）。
     * 直接毕业为 REVIEW、到期时间设为今天 4 点（已到期），它今天就出现在复习队列。
     *
     * - LEARNING：毕业到第一档（1 天）
     * - RELEARNING：忘词重记卡，间隔取 max(1, 旧间隔×0.5)，stepIndex 对齐阶梯档
     * 返回受影响行数。
     */
    @Query(
        """
        UPDATE review_states
        SET stage = 'REVIEW',
            stepIndex = 0,
            intervalDays = 1.0,
            dueAt = :cutoff
        WHERE stage = 'LEARNING' AND dueAt < :cutoff
        """
    )
    suspend fun graduateStaleLearningCards(cutoff: Long): Int

    @Query(
        """
        UPDATE review_states
        SET stage = 'REVIEW',
            intervalDays = MAX(1.0, intervalDays * 0.5),
            stepIndex = CASE
                WHEN MAX(1.0, intervalDays * 0.5) < 2 THEN 0
                WHEN MAX(1.0, intervalDays * 0.5) < 4 THEN 1
                WHEN MAX(1.0, intervalDays * 0.5) < 7 THEN 2
                WHEN MAX(1.0, intervalDays * 0.5) < 15 THEN 3
                ELSE 5
            END,
            dueAt = :cutoff
        WHERE stage = 'RELEARNING' AND dueAt < :cutoff
        """
    )
    suspend fun graduateStaleRelearningCards(cutoff: Long): Int

    /** 导出数据用：全部调度状态连着词头一起拿。 */
    @Query(
        """
        SELECT rs.*, w.term AS term
        FROM review_states AS rs
        INNER JOIN words AS w ON w.id = rs.wordId
        WHERE rs.stage != 'NEW'
        """
    )
    suspend fun getAllLearnedWithTerm(): List<StateWithTerm>
}
