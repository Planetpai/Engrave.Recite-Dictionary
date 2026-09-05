package com.example.vocabwatch.domain.repository

import com.example.vocabwatch.domain.model.ImportResult
import com.example.vocabwatch.domain.model.Word
import com.example.vocabwatch.domain.model.WordBook
import com.example.vocabwatch.domain.model.WordBookSource
import kotlinx.coroutines.flow.Flow

/** 词条和词书的读写。 */
interface WordRepository {
    fun observeBooks(): Flow<List<WordBook>>

    fun observeWords(bookId: Long?): Flow<List<Word>>

    suspend fun getBook(bookId: Long): WordBook?

    suspend fun getWord(wordId: Long): Word?

    /** 按 sourceKey 幂等导入：同一本词书重复调用不会插第二遍。 */
    suspend fun importBook(source: WordBookSource): ImportResult
}
