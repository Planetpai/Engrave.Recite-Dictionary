package com.example.vocabwatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("SELECT COUNT(*) FROM words WHERE (:bookId IS NULL OR bookId = :bookId)")
    fun observeCount(bookId: Long?): Flow<Int>

    @Query("SELECT COUNT(*) FROM words WHERE (:bookId IS NULL OR bookId = :bookId)")
    suspend fun count(bookId: Long?): Int

    @Insert
    suspend fun insertAll(words: List<WordEntity>): List<Long>
}
