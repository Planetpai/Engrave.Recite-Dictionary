package com.example.vocabwatch.data.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.vocabwatch.domain.model.DictEntry
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

    /** 确保词典文件已就位。重复调用走快速路径。 */
    suspend fun ensureReady() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!dbFile.exists() || dbFile.length() == 0L) {
                context.assets.open(ASSET_PATH).use { input ->
                    dbFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /**
     * 模糊搜索：精确命中 > 前缀命中 > 包含命中，同档内按词频（越常考越靠前）。
     * 词条量在十万级，LIKE 全扫在手表上也是几十毫秒的事，不用上 FTS。
     */
    suspend fun search(rawQuery: String, limit: Int = DEFAULT_LIMIT): List<DictEntry> {
        val query = rawQuery.trim().lowercase()
        if (query.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            ensureReady()
            open().use { db ->
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
    }

    suspend fun lookup(term: String): DictEntry? = withContext(Dispatchers.IO) {
        ensureReady()
        open().use { db ->
            queryEntries(db, "word = ? COLLATE NOCASE LIMIT 1", arrayOf(term.trim())).firstOrNull()
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
            ensureReady()
            open().use { db ->
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
    }

    private fun open(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)

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
        const val DICT_VERSION = 1

        private const val DEFAULT_LIMIT = 30

        /** 太短的片段做包含匹配会刷出满屏噪音。 */
        private const val MIN_CONTAINS_LENGTH = 2

        /** CJK 统一表意文字区起点，查询里出现就按中文释义搜索。 */
        private const val CJK_THRESHOLD = 0x2E80

        /** 派生词的词根还原用的常见后缀，长的优先匹配。 */
        private val SUFFIXES = listOf(
            "ation", "ition", "sion", "tion", "ment", "ness", "ance", "ence",
            "able", "ible", "ally", "ing", "ies", "ely", "ive", "ity", "ful",
            "less", "est", "ed", "es", "er", "ly", "al", "ic", "ent", "ant",
            "ion", "y", "s", "e"
        )
    }
}
