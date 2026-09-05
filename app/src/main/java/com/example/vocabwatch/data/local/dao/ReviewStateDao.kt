package com.example.vocabwatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.vocabwatch.data.local.StageCounts
import com.example.vocabwatch.data.local.StudyItemRow
import com.example.vocabwatch.data.local.entity.ReviewStateEntity
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
          AND (:bookId IS NULL OR w.bookId = :bookId)
        ORDER BY rs.dueAt ASC
        LIMIT :limit
        """
    )
    suspend fun getDueItems(bookId: Long?, now: Long, limit: Int): List<StudyItemRow>

    /** 还没学过的新词，按词书内的原始顺序出。 */
    @Query(
        """
        SELECT rs.*, w.bookId AS bookId, w.term AS term, w.meaning AS meaning,
               w.phonetic AS phonetic, w.example AS example,
               w.exampleTranslation AS exampleTranslation, w.position AS position
        FROM review_states AS rs
        INNER JOIN words AS w ON w.id = rs.wordId
        WHERE rs.stage = 'NEW'
          AND (:bookId IS NULL OR w.bookId = :bookId)
        ORDER BY w.bookId ASC, w.position ASC, w.id ASC
        LIMIT :limit
        """
    )
    suspend fun getNewItems(bookId: Long?, limit: Int): List<StudyItemRow>

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
        WHERE (:bookId IS NULL OR w.bookId = :bookId)
        """
    )
    fun observeStageCounts(bookId: Long?, now: Long): Flow<StageCounts>

    @Query("SELECT COUNT(*) FROM review_states WHERE stage = 'NEW'")
    suspend fun countNew(): Int
}
