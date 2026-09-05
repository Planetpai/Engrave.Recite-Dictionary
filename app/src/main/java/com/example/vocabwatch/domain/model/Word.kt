package com.example.vocabwatch.domain.model

/** 一个词条。id 为 0 表示尚未入库。 */
data class Word(
    val id: Long,
    val bookId: Long,
    val term: String,
    val meaning: String,
    val phonetic: String? = null,
    val example: String? = null,
    val exampleTranslation: String? = null,
    val position: Int = 0
)

/** 词书，例如「四级核心词」。sourceKey 用于幂等导入。 */
data class WordBook(
    val id: Long,
    val name: String,
    val description: String,
    val sourceKey: String?,
    val wordCount: Int,
    val createdAt: Long
)

/** 导入前的词条，不含任何 id。 */
data class WordEntry(
    val term: String,
    val meaning: String,
    val phonetic: String? = null,
    val example: String? = null,
    val exampleTranslation: String? = null
)

/** 一份待导入的词书。sourceKey 相同则视为同一本，不会重复导入。 */
data class WordBookSource(
    val name: String,
    val description: String,
    val sourceKey: String,
    val entries: List<WordEntry>
)

data class ImportResult(
    val bookId: Long,
    val importedCount: Int,
    val alreadyImported: Boolean
)
