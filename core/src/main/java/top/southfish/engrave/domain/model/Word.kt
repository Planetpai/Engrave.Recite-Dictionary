package top.southfish.engrave.domain.model

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

/**
 * 离线词典（ECDICT）的一条查询结果。
 *
 * meaningZh 对应 ECDICT 的 translation 列（中文释义），definitionEn 对应
 * definition 列（英文释义）。score 是搜索时的匹配档位：3 精确、2 前缀、1 包含。
 */
data class DictEntry(
    val term: String,
    val phonetic: String? = null,
    val meaningZh: String? = null,
    val definitionEn: String? = null,
    val exchange: String? = null,
    val score: Int = 0,
    /** COCA 语料词频，越大越常用；0 表示语料库未收录。 */
    val frq: Int = 0
)

/** 词典里的一条短语（phrase 表）。srcWord 是挂载它的来源词。 */
data class PhraseEntry(
    val phrase: String,
    val zh: String,
    val srcWord: String
)

/**
 * 一个词的原型/变形信息，由 DictRepository.parseExchange 从 exchange 字段解出。
 * lemma：该词的原型（若该词本身是变形词）；selfRelation：该词相对原型的关系码；
 * forms：该词自身列出的各变形（关系码 → 变形词）。
 */
data class ExchangeInfo(
    val lemma: String? = null,
    val selfRelation: String? = null,
    val forms: Map<String, String> = emptyMap()
)
