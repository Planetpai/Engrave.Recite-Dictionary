package com.example.vocabwatch.data.repository

import androidx.room.withTransaction
import com.example.vocabwatch.data.local.AppDatabase
import com.example.vocabwatch.data.local.dao.ReviewStateDao
import com.example.vocabwatch.data.local.dao.WordBookDao
import com.example.vocabwatch.data.local.dao.WordDao
import com.example.vocabwatch.data.local.entity.ReviewStateEntity
import com.example.vocabwatch.data.local.entity.WordBookEntity
import com.example.vocabwatch.data.local.entity.WordEntity
import com.example.vocabwatch.data.mapper.toDomain
import com.example.vocabwatch.domain.model.ImportResult
import com.example.vocabwatch.domain.model.LearningStage
import com.example.vocabwatch.domain.model.ReviewState
import com.example.vocabwatch.domain.model.Word
import com.example.vocabwatch.domain.model.WordBook
import com.example.vocabwatch.domain.model.WordBookSource
import com.example.vocabwatch.domain.repository.WordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class WordRepositoryImpl(
    private val database: AppDatabase,
    private val wordBookDao: WordBookDao,
    private val wordDao: WordDao,
    private val reviewStateDao: ReviewStateDao
) : WordRepository {

    override fun observeBooks(): Flow<List<WordBook>> =
        wordBookDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeWords(bookId: Long?): Flow<List<Word>> =
        wordDao.observeAll(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun getBook(bookId: Long): WordBook? =
        wordBookDao.getById(bookId)?.toDomain()

    override suspend fun getWord(wordId: Long): Word? =
        wordDao.getById(wordId)?.toDomain()

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
