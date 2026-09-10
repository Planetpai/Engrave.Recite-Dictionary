package top.southfish.engrave.domain.repository

import top.southfish.engrave.domain.model.ImportResult
import top.southfish.engrave.domain.model.Word
import top.southfish.engrave.domain.model.WordBook
import top.southfish.engrave.domain.model.WordBookSource
import kotlinx.coroutines.flow.Flow

/** 词条和词书的读写。 */
interface WordRepository {
    fun observeBooks(): Flow<List<WordBook>>

    fun observeWords(bookId: Long?): Flow<List<Word>>

    /** 每本词书内容占用的估算字节数（词头+释义+例句等文本长度）。 */
    fun observeBookSizes(): Flow<Map<Long, Long>>

    suspend fun getBook(bookId: Long): WordBook?

    /** 全部词书快照（启动清理下架内置词书用）。 */
    suspend fun getBooksSnapshot(): List<WordBook>

    suspend fun getWord(wordId: Long): Word?

    /** 词书内全部词条，导出数据用。 */
    suspend fun getWords(bookId: Long): List<Word>

    /** 按词头跨词书查找（忽略大小写），详情页找例句、导入回填状态用。 */
    suspend fun findWord(term: String): Word?

    /** 删除本地词书；默认词书由调用方拦截。 */
    suspend fun deleteBook(bookId: Long)

    /**
     * 按来源 key 删除词书（下架随包内置词书时清理存量用）。
     * 返回被删词书的 id；不存在返回 null。外键级联会带走词条和调度状态。
     */
    suspend fun deleteBookBySourceKey(sourceKey: String): Long?

    /** 按 sourceKey 幂等导入：同一本词书重复调用不会插第二遍。 */
    suspend fun importBook(source: WordBookSource): ImportResult
}
