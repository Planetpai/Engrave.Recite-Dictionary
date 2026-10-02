package top.southfish.engrave.data.repository

import androidx.room.withTransaction
import top.southfish.engrave.data.local.AppDatabase
import top.southfish.engrave.data.local.dao.ReviewStateDao
import top.southfish.engrave.data.local.dao.WordBookDao
import top.southfish.engrave.data.local.dao.WordDao
import top.southfish.engrave.data.local.entity.ReviewStateEntity
import top.southfish.engrave.data.local.entity.WordBookEntity
import top.southfish.engrave.data.local.entity.WordEntity
import top.southfish.engrave.data.mapper.toDomain
import top.southfish.engrave.domain.model.ImportResult
import top.southfish.engrave.domain.model.LearningStage
import top.southfish.engrave.domain.model.ReviewState
import top.southfish.engrave.domain.model.Word
import top.southfish.engrave.domain.model.WordBook
import top.southfish.engrave.domain.model.WordBookSource
import top.southfish.engrave.domain.repository.WordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class WordRepositoryImpl(
    private val database: AppDatabase,
    private val wordBookDao: WordBookDao,
    private val wordDao: WordDao,
    private val reviewStateDao: ReviewStateDao
) : WordRepository {

    override fun observeBooks(): Flow<List<WordBook>> =
        wordBookDao.observeAll().map { list ->
            // 自然排序：pepgz1 < pepgz2 < ... < pepgz10；内置书按 sourceKey（年级/册/单元编号有序），
            // 自定义导入书兜底按书名。
            list.map { it.toDomain() }.sortedWith(
                compareBy(naturalStringOrder) { it.sourceKey ?: it.name }
            )
        }

    /** 把数字段按数值比较、其余字符按码点比较的自然序，避免「U10 排在 U2 前」。 */
    private val naturalStringOrder = Comparator<String> { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var i2 = i
                while (i2 < a.length && a[i2].isDigit()) i2++
                var j2 = j
                while (j2 < b.length && b[j2].isDigit()) j2++
                val na = a.substring(i, i2).trimStart('0').ifEmpty { "0" }
                val nb = b.substring(j, j2).trimStart('0').ifEmpty { "0" }
                val cmp = na.length.compareTo(nb.length).takeIf { it != 0 } ?: na.compareTo(nb)
                if (cmp != 0) return@Comparator cmp
                i = i2
                j = j2
            } else {
                val cmp = a[i].compareTo(b[j])
                if (cmp != 0) return@Comparator cmp
                i++
                j++
            }
        }
        a.length - b.length
    }

    override fun observeWords(bookId: Long?): Flow<List<Word>> =
        wordDao.observeAll(bookId).map { list -> list.map { it.toDomain() } }

    override fun observeBookSizes(): Flow<Map<Long, Long>> =
        wordDao.observeBookSizes().map { list -> list.associate { it.bookId to it.bytes } }

    override suspend fun getBook(bookId: Long): WordBook? =
        wordBookDao.getById(bookId)?.toDomain()

    override suspend fun getBooksSnapshot(): List<WordBook> =
        wordBookDao.getAllOnce().map { it.toDomain() }

    override suspend fun getWord(wordId: Long): Word? =
        wordDao.getById(wordId)?.toDomain()

    override suspend fun getWords(bookId: Long): List<Word> =
        wordDao.getByBook(bookId).map { it.toDomain() }

    override suspend fun findWord(term: String): Word? =
        wordDao.findByTerm(term.trim())?.toDomain()

    override suspend fun deleteBook(bookId: Long) {
        wordBookDao.deleteById(bookId)
    }

    override suspend fun deleteBookBySourceKey(sourceKey: String): Long? {
        val book = wordBookDao.getBySourceKey(sourceKey) ?: return null
        wordBookDao.deleteById(book.id)
        return book.id
    }

    /**
     * 导入一本词书。整个过程在一个事务里：
     * 插词书 → 插词条 → 为每个词预建 NEW 状态的调度行。
     *
     * sourceKey 上有唯一索引，所以重复导入会命中已有记录直接返回，
     * 不会插出第二份词表。首次安装后反复启动都走这条快速路径。
     */
    override suspend fun importBook(source: WordBookSource): ImportResult =
        database.withTransaction {
            wordBookDao.getBySourceKey(source.sourceKey)?.let { existing ->
                return@withTransaction ImportResult(
                    bookId = existing.id,
                    importedCount = 0,
                    alreadyImported = true
                )
            }

            val bookId = wordBookDao.insert(
                WordBookEntity(
                    name = source.name,
                    description = source.description,
                    sourceKey = source.sourceKey,
                    wordCount = source.entries.size,
                    createdAt = System.currentTimeMillis()
                )
            )

            // 词书数据可能来自第三方文件，同一条目重复会撞上 (bookId, term) 唯一索引，
            // 所以按 term 去重，保留第一次出现的顺序
            val distinctEntries = source.entries
                .distinctBy { it.term.lowercase() }

            val wordIds = wordDao.insertAll(
                distinctEntries.mapIndexed { index, entry ->
                    WordEntity(
                        bookId = bookId,
                        term = entry.term,
                        meaning = entry.meaning,
                        phonetic = entry.phonetic,
                        example = entry.example,
                        exampleTranslation = entry.exampleTranslation,
                        position = index
                    )
                }
            )

            reviewStateDao.upsertAll(
                wordIds.map { wordId ->
                    ReviewStateEntity(
                        wordId = wordId,
                        stage = LearningStage.NEW.name,
                        stepIndex = 0,
                        intervalDays = 0.0,
                        easeFactor = ReviewState.DEFAULT_EASE_FACTOR,
                        repetitions = 0,
                        lapses = 0,
                        dueAt = 0L,
                        lastReviewedAt = null
                    )
                }
            )

            ImportResult(
                bookId = bookId,
                importedCount = distinctEntries.size,
                alreadyImported = false
            )
        }
}
