package com.example.vocabwatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.vocabwatch.data.local.entity.MistakeEntity
import com.example.vocabwatch.data.local.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {

    @Query("SELECT * FROM search_history ORDER BY lookedUpAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE term = :term")
    suspend fun delete(term: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface MistakeDao {

    @Query("SELECT * FROM mistake_words ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<MistakeEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM mistake_words WHERE term = :term)")
    fun observeExists(term: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM mistake_words WHERE term = :term)")
    suspend fun exists(term: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MistakeEntity)

    @Query("DELETE FROM mistake_words WHERE term = :term")
    suspend fun delete(term: String)
}
