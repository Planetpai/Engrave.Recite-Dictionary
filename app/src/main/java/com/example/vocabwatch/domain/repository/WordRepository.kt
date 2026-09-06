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

    /** 每本词书内容占用的估算字节数（词头+释义+例句等文本长度）。 */
    fun observeBookSizes(): Flow<Map<Long, Long>>

    suspend fun getBook(bookId: Long): WordBook?

    suspend fun getWord(wordId: Long): Word?

    /** 词书内全部词条，导出数据用。 */
    suspend fun getWords(bookId: Long): List<Word>

    /** 按词头跨词书查找（忽略大小写），详情页找例句、导入回填状态用。 */
    suspend fun findWord(term: String): Word?

    /** 删除本地词书；默认词书由调用方拦截。 */
    suspend fun deleteBook(bookId: Long)

    /** 按 sourceKey 幂等导入：同一本词书重复调用不会插第二遍。 */
    suspend fun importBook(source: WordBookSource): ImportResult
}
