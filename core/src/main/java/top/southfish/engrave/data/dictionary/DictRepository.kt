package top.southfish.engrave.data.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import top.southfish.engrave.domain.model.DictEntry
import top.southfish.engrave.domain.model.ExchangeInfo
import top.southfish.engrave.domain.model.PhraseEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ECDICT 离线词典（skywind3000/ECDICT，MIT 协议）。
 *
 * 打包的是按词频 + 考纲标签筛出的子集 SQLite（tools/build_dict.py 生成），
 * 只读，不走 Room：首次访问时从 assets 拷到 filesDir，之后直接打开。
 * 完全离线，不依赖任何网络。
 */
class DictRepository(private val context: Context) {

    private val lock = Mutex()
    private val dbFile: File get() = File(context.filesDir, DICT_FILE)

    /**
     * 常驻只读连接。旧实现每次 search/lookup/derivatives 都 open()+use{} 关一遍，
     * 每次都要重新打开文件、SQLite 页缓存全冷——搜索一次要跑 2~4 条 LIKE，
     * 连续输入时反复开关连接是纯浪费。现在打开一次保活，热词页缓存常驻。
     */
    @Volatile
    private var database: SQLiteDatabase? = null

    /** 确保词典文件已就位。重复调用走快速路径。 */
    suspend fun ensureReady() = withContext(Dispatchers.IO) {
        lock.withLock { copyAssetIfNeeded() }
    }

    /** 取常驻连接；首次调用时拷贝 assets 并打开（含调大页缓存）。 */
    private suspend fun openDb(): SQLiteDatabase = withContext(Dispatchers.IO) {
        database?.takeIf { it.isOpen }?.let { return@withContext it }
        lock.withLock {
            val existing = database?.takeIf { it.isOpen }
            if (existing != null) {
                existing
            } else {
                copyAssetIfNeeded()
                SQLiteDatabase.openDatabase(
                    dbFile.path, null, SQLiteDatabase.OPEN_READONLY
                ).also { db ->
                    // 只读词典：页缓存 ~2MB。索引点查（实测 ~0.04ms）吃不到这么大的
                    // 缓存，表连接常驻才是 10× 的大头；手表内存紧张，省下 2MB 私有内存。
                    db.execSQL("PRAGMA cache_size=-2048")
                    db.execSQL("PRAGMA temp_store=MEMORY")
                    database = db
                }
            }
        }
    }

    private fun copyAssetIfNeeded() {
        // 版本机制：assets 里的词典升级（如新增 phrase 表）后 DICT_VERSION 自增，
        // 已拷贝到 filesDir 的旧库要删掉重拷。版本号落在旁边的 .ver 标记文件里。
        val verFile = File(context.filesDir, "$DICT_FILE.ver")
        val installedVer = if (dbFile.exists()) {
            verFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        } else {
            -1
        }
        if (installedVer >= DICT_VERSION && dbFile.length() > 0L) return
        database?.takeIf { it.isOpen }?.close()
        database = null
        dbFile.delete()
        context.assets.open(ASSET_PATH).use { input ->
            dbFile.outputStream().use { output -> input.copyTo(output) }
        }
        verFile.writeText(DICT_VERSION.toString())
    }

    /**
     * 模糊搜索：精确命中 > 前缀命中 > 包含命中，同档内按词频（越常考越靠前）。
     * 词条量在十万级，LIKE 全扫在手表上也是几十毫秒的事，不用上 FTS。
     */
    suspend fun search(rawQuery: String, limit: Int = DEFAULT_LIMIT): List<DictEntry> {
        val query = rawQuery.trim().lowercase()
        if (query.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val db = openDb()
            // 中文查询：直接在释义里找。排序三档：首行主义项以查询开头 >
            // 首行包含 > 其它（ECDICT 的 frq 在生僻词上虚高，只作同档参考）
            if (query.any { it.code > CJK_THRESHOLD }) {
                queryEntries(
                    db,
                    "zh LIKE ? ESCAPE '\\' LIMIT 120",
                    arrayOf("%${escapeLike(query)}%")
                ).sortedWith(
                    compareBy<DictEntry> { entry ->
                        val firstLine = entry.meaningZh?.lineSequence()?.firstOrNull()
                            ?.replace(Regex("^[a-z]+\\.\\s*"), "") ?: ""
                        when {
                            firstLine.startsWith(query) -> 0
                            firstLine.contains(query) -> 1
                            else -> 2
                        }
                    }.thenBy { it.term.length }.thenByDescending { it.frq }
                )
                    .map { it.copy(score = 1) }
                    .take(limit)
            } else {
                val exact = queryEntries(db, "word = ? LIMIT 1", arrayOf(query))
                val prefix = queryEntries(
                    db, "word LIKE ? ESCAPE '\\' AND word != ? ORDER BY frq DESC LIMIT ?",
                    arrayOf("${escapeLike(query)}%", query, limit.toString())
                )
                val contains = if (query.length >= MIN_CONTAINS_LENGTH) {
                    queryEntries(
                        db,
                        "word LIKE ? ESCAPE '\\' AND word NOT LIKE ? ORDER BY frq DESC LIMIT ?",
                        arrayOf("%${escapeLike(query)}%", "${escapeLike(query)}%", limit.toString())
                    )
                } else {
                    emptyList()
                }
                (exact.map { it.copy(score = 3) } +
                    prefix.map { it.copy(score = 2) } +
                    contains.map { it.copy(score = 1) })
                    .distinctBy { it.term }
                    .take(limit)
            }
        }
    }

    suspend fun lookup(term: String): DictEntry? = withContext(Dispatchers.IO) {
        val db = openDb()
        queryEntries(db, "word = ? COLLATE NOCASE LIMIT 1", arrayOf(term.trim())).firstOrNull()
    }

    /**
     * 词形变化关联：解析词条 exchange 字段（ECDICT 格式 `p:过去式/d:过去分词/
     * i:现在分词/3:三单/r:比较级/t:最高级/s:复数/0:原型/1:lemma变形码`）。
     *
     * 返回两类信息：
     * - [ExchangeInfo.lemma]：若该词是变形词（exchange 含 0:xxx），其原型词；
     * - [ExchangeInfo.forms]：exchange 里列出的各变形（带关系名）。
     * 展示层负责把关系码翻成中文、把能查到的词做成可点跳转。
     */
    suspend fun exchangeInfo(term: String): ExchangeInfo? = withContext(Dispatchers.IO) {
        val entry = lookup(term) ?: return@withContext null
        parseExchange(entry.exchange)
    }

    /** 短语搜索：英文短语前缀/包含 + 中文释义包含，混合进搜索结果。 */
    suspend fun searchPhrases(rawQuery: String, limit: Int = 8): List<PhraseEntry> {
        val query = rawQuery.trim().lowercase()
        if (query.length < 2) return emptyList()
        return withContext(Dispatchers.IO) {
            val db = openDb()
            val isCjk = query.any { it.code > CJK_THRESHOLD }
            val sql = if (isCjk) {
                "zh LIKE ? ESCAPE '\\' ORDER BY length(phrase) LIMIT ?"
            } else {
                "phrase LIKE ? ESCAPE '\\' ORDER BY length(phrase) LIMIT ?"
            }
            val arg = if (isCjk) "%${escapeLike(query)}%" else "${escapeLike(query)}%"
            val out = mutableListOf<PhraseEntry>()
            db.query(
                "phrase", arrayOf("phrase", "zh", "src_word"),
                sql, arrayOf(arg, limit.toString()), null, null, null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    out.add(
                        PhraseEntry(
                            phrase = cursor.getString(0) ?: continue,
                            zh = cursor.getString(1) ?: "",
                            srcWord = cursor.getString(2) ?: ""
                        )
                    )
                }
            }
            out
        }
    }

    /** 详情页「关联短语」：列出所有挂在该词下的短语（按长度排，短的在前面更常用）。 */
    suspend fun relatedPhrases(term: String, limit: Int = 20): List<PhraseEntry> {
        val t = term.trim().lowercase()
        if (t.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val db = openDb()
            val out = mutableListOf<PhraseEntry>()
            db.query(
                "phrase", arrayOf("phrase", "zh", "src_word"),
                "src_word = ? COLLATE NOCASE ORDER BY length(phrase) LIMIT ?",
                arrayOf(t, limit.toString()), null, null, null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    out.add(
                        PhraseEntry(
                            phrase = cursor.getString(0) ?: continue,
                            zh = cursor.getString(1) ?: "",
                            srcWord = cursor.getString(2) ?: ""
                        )
                    )
                }
            }
            out
        }
    }

    /**
     * 派生词：以词本身和剥掉一层常见后缀后的词根做前缀查询（different →
     * differently/differential；difference → 剥 ence → differ → differently）。
     * 排序按词长接近度（越像原词越靠前）再按词频。离线启发式，够展示
     * 「同根词 + 词性 + 释义」；以后接词典数据源可以换更准的。
     */
    suspend fun derivatives(term: String, limit: Int = 6): List<DictEntry> {
        val t = term.trim().lowercase()
        if (t.length < 3) return emptyList()
        val prefixes = linkedSetOf(t)
        val suffix = SUFFIXES.firstOrNull { t.endsWith(it) && t.length - it.length >= 3 }
        if (suffix != null) prefixes.add(t.dropLast(suffix.length))
        return withContext(Dispatchers.IO) {
            val db = openDb()
            val out = LinkedHashMap<String, DictEntry>()
            for (prefix in prefixes) {
                queryEntries(
                    db,
                    "word LIKE ? ESCAPE '\\' AND word != ? COLLATE NOCASE ORDER BY frq DESC LIMIT 15",
                    arrayOf("${escapeLike(prefix)}%", t)
                ).forEach { entry ->
                    if (entry.term.length <= t.length + 8 && !entry.meaningZh.isNullOrBlank()) {
                        out.putIfAbsent(entry.term.lowercase(), entry)
                    }
                }
            }
            out.values
                .sortedWith(compareBy<DictEntry> { it.term.length }.thenByDescending { it.frq })
                .take(limit)
        }
    }

    private fun queryEntries(
        db: SQLiteDatabase,
        selection: String,
        args: Array<String>
    ): List<DictEntry> {
        val out = mutableListOf<DictEntry>()
        db.query(
            "dict", arrayOf("word", "phonetic", "zh", "en", "exchange", "frq"),
            selection, args, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    DictEntry(
                        term = cursor.getString(0) ?: continue,
                        phonetic = cursor.getString(1)?.takeIf { it.isNotBlank() },
                        meaningZh = cursor.getString(2)?.takeIf { it.isNotBlank() },
                        definitionEn = cursor.getString(3)?.takeIf { it.isNotBlank() },
                        exchange = cursor.getString(4)?.takeIf { it.isNotBlank() },
                        frq = cursor.getInt(5)
                    )
                )
            }
        }
        return out
    }

    private fun escapeLike(query: String): String =
        query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    companion object {
        const val DICT_FILE = "ecdict_core.db"
        const val ASSET_PATH = "dict/ecdict_core.db"
        // 2: 新增 phrase 表（build_phrases.py）
        const val DICT_VERSION = 2

        private const val DEFAULT_LIMIT = 30

        /** 太短的片段做包含匹配会刷出满屏噪音。 */
        private const val MIN_CONTAINS_LENGTH = 2

        /** CJK 统一表意文字区起点，查询里出现就按中文释义搜索。 */
        private const val CJK_THRESHOLD = 0x2E80

        /**
         * ECDICT exchange 关系码 → 中文关系名。展示层拿这个把码翻成人话。
         * p 过去式 / d 过去分词 / i 现在分词 / 3 三单 / r 比较级 / t 最高级 / s 复数。
         */
        val EXCHANGE_LABELS = linkedMapOf(
            "p" to "过去式",
            "d" to "过去分词",
            "i" to "现在分词",
            "3" to "第三人称单数",
            "r" to "比较级",
            "t" to "最高级",
            "s" to "复数"
        )

        /**
         * 解析 exchange 字段为结构化信息。纯函数，便于 JVM 单测。
         * 格式：`p:played/d:played/i:playing/3:plays/s:plays/0:play/1:xyz`
         * - 0:xxx → 原型词（lemma）
         * - 其它码 → 该词自身的变形（key=关系码, value=变形词）
         * 注意 ECDICT 里变形词的 exchange（如 dying 的 `0:die/1:i`）用 1: 标关系码、
         * 0: 标原型；原型词的 exchange（如 die）则列出各变形。两种都要能解。
         */
        fun parseExchange(exchange: String?): ExchangeInfo? {
            if (exchange.isNullOrBlank()) return null
            var lemma: String? = null
            var selfRelation: String? = null
            val forms = LinkedHashMap<String, String>()
            for (part in exchange.split("/")) {
                val idx = part.indexOf(':')
                if (idx <= 0 || idx == part.length - 1) continue
                val code = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (value.isEmpty()) continue
                when (code) {
                    "0" -> lemma = value
                    "1" -> selfRelation = value  // 变形码（i/p/d/s…），该词相对原型的关系
                    else -> if (EXCHANGE_LABELS.containsKey(code)) forms[code] = value
                }
            }
            if (lemma == null && forms.isEmpty()) return null
            return ExchangeInfo(lemma = lemma, selfRelation = selfRelation, forms = forms)
        }
    }

    /** 派生词的词根还原用的常见后缀，长的优先匹配。 */
    private val SUFFIXES = listOf(
        "ation", "ition", "sion", "tion", "ment", "ness", "ance", "ence",
        "able", "ible", "ally", "ing", "ies", "ely", "ive", "ity", "ful",
        "less", "est", "ed", "es", "er", "ly", "al", "ic", "ent", "ant",
        "ion", "y", "s", "e"
    )
}
