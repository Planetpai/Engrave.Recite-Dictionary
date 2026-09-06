package com.example.vocabwatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.vocabwatch.data.local.BookSize
import com.example.vocabwatch.data.local.entity.WordBookEntity
import com.example.vocabwatch.data.local.entity.WordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WordBookDao {

    @Query("SELECT * FROM word_books ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<WordBookEntity>>

    @Query("SELECT * FROM word_books WHERE id = :bookId")
    suspend fun getById(bookId: Long): WordBookEntity?

    @Query("SELECT * FROM word_books WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun getBySourceKey(sourceKey: String): WordBookEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(book: WordBookEntity): Long

    /** 外键都配了 CASCADE，删词书会带走词条和调度状态。 */
    @Query("DELETE FROM word_books WHERE id = :bookId")
    suspend fun deleteById(bookId: Long)
}

@Dao
interface WordDao {

    /** bookId 传 null 表示不限词书。 */
    @Query(
        """
        SELECT * FROM words
        WHERE (:bookId IS NULL OR bookId = :bookId)
        ORDER BY bookId ASC, position ASC, id ASC
        """
    )
    fun observeAll(bookId: Long?): Flow<List<WordEntity>>

    @Query("SELECT * FROM words WHERE id = :wordId")
    suspend fun getById(wordId: Long): WordEntity?

    @Query("SELECT * FROM words WHERE bookId = :bookId ORDER BY position ASC, id ASC")
    suspend fun getByBook(bookId: Long): List<WordEntity>

    /** 词头跨词书查找：搜索详情要找例句、导入状态要回填 wordId。 */
    @Query("SELECT * FROM words WHERE term = :term COLLATE NOCASE LIMIT 1")
    suspend fun findByTerm(term: String): WordEntity?

    @Query("SELECT COUNT(*) FROM words WHERE (:bookId IS NULL OR bookId = :bookId)")
    fun observeCount(bookId: Long?): Flow<Int>

    @Query("SELECT COUNT(*) FROM words WHERE (:bookId IS NULL OR bookId = :bookId)")
    suspend fun count(bookId: Long?): Int

    /** 每本词书内容占用的估算字节数，词书管理页展示。 */
    @Query(
        """
        SELECT bookId AS bookId,
            COALESCE(SUM(
                LENGTH(term) + LENGTH(meaning) +
                LENGTH(IFNULL(phonetic, '')) + LENGTH(IFNULL(example, '')) +
                LENGTH(IFNULL(exampleTranslation, '')) + 64
            ), 0) AS bytes
        FROM words GROUP BY bookId
        """
    )
    fun observeBookSizes(): Flow<List<BookSize>>

    @Insert
    suspend fun insertAll(words: List<WordEntity>): List<Long>
}
