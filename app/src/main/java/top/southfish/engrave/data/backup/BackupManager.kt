package top.southfish.engrave.data.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import top.southfish.engrave.data.local.AppDatabase
import top.southfish.engrave.data.local.entity.ReviewStateEntity
import top.southfish.engrave.data.seed.SeedDataProvider
import top.southfish.engrave.data.seed.WordBookJsonParser
import top.southfish.engrave.domain.model.DefinitionDisplay
import top.southfish.engrave.domain.model.WordBookSource
import top.southfish.engrave.domain.model.WordEntry
import top.southfish.engrave.data.session.StudySessionStore
import top.southfish.engrave.reminder.ReminderScheduler
import top.southfish.engrave.domain.repository.SettingsRepository
import top.southfish.engrave.domain.repository.WordRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 数据导出 / 导入。
 *
 * 导出包是一个自包含 JSON：用户资料、背单词习惯设置、当前词书本体、
 * 已学（含未学）词的调度状态和版本号。词书的导出单独走 [exportBookToDownloads]，
 * 产物和 assets 里词书 JSON 同构，可以直接再导入。
 *
 * 手表上没有 SAF 文件选择器（点导出会报 no activity can handle this action），
 * 所以读写都走 MediaStore 的 Download/vocab/ 目录：写入无需任何权限，
 * 读回的也是本应用自己写的文件，同样免权限。
 */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val wordRepository: WordRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionStore: StudySessionStore,
    private val seeder: SeedDataProvider
) {

    /** 导出包在 Download/vocab/ 里的一个候选文件。 */
    data class BackupFile(val displayName: String, val uri: Uri)

    /** 全量导出到 Download/vocab/。返回生成的文件名。 */
    suspend fun exportAllToDownloads(): String = withContext(Dispatchers.IO) {
        val config = settingsRepository.queueConfig.first()
        val profile = settingsRepository.profile.first()
        val reminder = settingsRepository.reminderMinutes.first()
        val books = wordRepository.observeBooks().first()

        val root = JSONObject().apply {
            put(KEY_FORMAT, FORMAT_VERSION)
            put("userName", profile.userName)
            put("avatarIndex", profile.avatarIndex)
            put("dailyNewLimit", config.dailyNewLimit)
            put("definitionDisplay", settingsRepository.definitionDisplay.first().name)
            reminder?.let { put("reminderMinutes", it) }
            // 启用的词书集合（多选）；空集合=全部词书，不写键
            if (config.activeBookIds.isNotEmpty()) {
                put("activeBookSourceKeys", JSONArray().apply {
                    config.activeBookIds.forEach { id ->
                        books.firstOrNull { it.id == id }?.sourceKey?.let { put(it) }
                    }
                })
            }

            put("books", JSONArray().apply {
                books.forEach { book ->
                    put(JSONObject().apply {
                        put("name", book.name)
                        put("description", book.description)
                        book.sourceKey?.let { put("sourceKey", it) }
                        put("words", JSONArray().apply {
                            wordRepository.getWords(book.id).forEach { word ->
                                put(JSONObject().apply {
                                    put("term", word.term)
                                    put("meaning", word.meaning)
                                    word.phonetic?.let { put("phonetic", it) }
                                    word.example?.let { put("example", it) }
                                    word.exampleTranslation?.let { put("exampleTranslation", it) }
                                })
                            }
                        })
                    })
                }
            })

            put("states", JSONArray().apply {
                database.reviewStateDao().getAllLearnedWithTerm().forEach { row ->
                    put(JSONObject().apply {
                        put("term", row.term)
                        put("stage", row.state.stage)
                        put("stepIndex", row.state.stepIndex)
                        put("intervalDays", row.state.intervalDays)
                        put("easeFactor", row.state.easeFactor)
                        put("repetitions", row.state.repetitions)
                        put("lapses", row.state.lapses)
                        put("dueAt", row.state.dueAt)
                        row.state.lastReviewedAt?.let { put("lastReviewedAt", it) }
                    })
                }
            })
        }

        val name = "vocab-backup-${STAMP_FORMAT.format(System.currentTimeMillis())}.json"
        writeDownload(name, root.toString(2).toByteArray(Charsets.UTF_8))
        name
    }

    /** 导出单本词书（默认词书也能导），产物和 assets 词书 JSON 同构。 */
    suspend fun exportBookToDownloads(bookId: Long): String = withContext(Dispatchers.IO) {
        val book = wordRepository.getBook(bookId) ?: throw IllegalArgumentException("词书不存在")
        val root = JSONObject().apply {
            put("name", book.name)
            put("description", book.description)
            put("words", JSONArray().apply {
                wordRepository.getWords(bookId).forEach { word ->
                    put(JSONObject().apply {
                        put("term", word.term)
                        put("meaning", word.meaning)
                        word.phonetic?.let { put("phonetic", it) }
                        word.example?.let { put("example", it) }
                        word.exampleTranslation?.let { put("exampleTranslation", it) }
                    })
                }
            })
        }
        val name = "book-${book.name}-${STAMP_FORMAT.format(System.currentTimeMillis())}.json"
        writeDownload(name, root.toString(2).toByteArray(Charsets.UTF_8))
        name
    }

    /**
     * 列出 Download/vocab/ 下的词书 JSON，新的在前。
     *
     * 两个来源合并：MediaStore（本应用导出的，免权限可读）+ File 直扫
     * （卸载重装后旧文件会失去属主，MediaStore 查不到也读不了，
     * 需要已授予「所有文件访问」权限才能兜底扫到）。
     */
    suspend fun listBackups(): List<BackupFile> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, BackupFile>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.MIME_TYPE + " = ?",
                arrayOf(DOWNLOAD_DIR + "%", "application/json"),
                MediaStore.MediaColumns.DATE_ADDED + " DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idCol)
                    )
                    out[name] = BackupFile(name, uri)
                }
            }
        }

        if (hasStorageReadPermission()) {
            runCatching {
                val dir: File = downloadDir()
                val files: List<File> = dir.listFiles()?.filter { it.isFile && it.extension.equals("json", true) } ?: emptyList()
                files.sortedByDescending { it.lastModified() }.forEach { file ->
                    out.putIfAbsent(file.name, BackupFile(file.name, Uri.fromFile(file)))
                }
            }
        }
        out.values.toList()
    }

    /**
     * 共享存储可读（传统存储 READ 授权，或「所有文件访问」）。
     * targetSdk 29 传统存储下读 Download 靠的就是旧式 READ_EXTERNAL_STORAGE。
     */
    private fun hasStorageReadPermission(): Boolean =
        Environment.isExternalStorageManager() ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 从 Uri 恢复全量数据。恢复是**破坏性**的：先清空现有词书、学习状态、
     * 错题集、搜索历史和设置（界面层负责弹确认框），再灌入备份内容。
     * 返回导入的词书数量。
     */
    suspend fun importAll(uri: Uri): Int = withContext(Dispatchers.IO) {
        val root = JSONObject(readText(uri))
        if (root.optInt(KEY_FORMAT, -1) > FORMAT_VERSION) {
            throw IllegalArgumentException("数据包版本过新，请先升级应用")
        }

        database.clearAllTables()
        settingsRepository.resetAll()
        sessionStore.clear()

        // 词书先落库（按 sourceKey 幂等），调度状态再按词头回填
        val books = root.optJSONArray("books") ?: JSONArray()
        var imported = 0
        for (i in 0 until books.length()) {
            val book = books.getJSONObject(i)
            val source = wordBookFromJson(
                name = book.getString("name"),
                description = book.optString("description"),
                sourceKey = book.optString("sourceKey")
                    .ifBlank { "import:" + book.getString("name").hashCode() },
                words = book.getJSONArray("words")
            )
            wordRepository.importBook(source)
            imported++
        }

        restoreStatesAndSettings(root)
        imported
    }

    /** 导入一本本地词书 JSON（Uri 来自文件选择器或 MediaStore），幂等。 */
    suspend fun importBook(uri: Uri): String = withContext(Dispatchers.IO) {
        val json = readText(uri)
        val probe = JSONObject(json)
        val name = probe.optString("name").ifBlank { "本地词书" }
        val sourceKey = "import:" + name + ":" + Integer.toHexString(json.hashCode())
        val source = WordBookJsonParser.parse(sourceKey = sourceKey, json = json)
            ?: throw IllegalArgumentException("文件不是有效的词书 JSON")
        wordRepository.importBook(source)
        source.name
    }

    private suspend fun restoreStatesAndSettings(root: JSONObject) {
        val states = root.optJSONArray("states") ?: JSONArray()
        for (i in 0 until states.length()) {
            val s = states.getJSONObject(i)
            val word = wordRepository.findWord(s.getString("term")) ?: continue
            database.reviewStateDao().upsert(
                ReviewStateEntity(
                    wordId = word.id,
                    stage = s.optString("stage", "LEARNING"),
                    stepIndex = s.optInt("stepIndex"),
                    intervalDays = s.optDouble("intervalDays", 0.0),
                    easeFactor = s.optDouble("easeFactor", 2.5),
                    repetitions = s.optInt("repetitions"),
                    lapses = s.optInt("lapses"),
                    dueAt = s.optLong("dueAt", 0L),
                    lastReviewedAt = if (s.has("lastReviewedAt")) s.getLong("lastReviewedAt") else null
                )
            )
        }

        // 资料和设置最后恢复；活动词书按 sourceKey 重新定位 id
        settingsRepository.setUserName(root.optString("userName"))
        settingsRepository.setAvatarIndex(root.optInt("avatarIndex", 0))
        settingsRepository.setDailyNewLimit(root.optInt("dailyNewLimit", 10))
        settingsRepository.setDefinitionDisplay(
            DefinitionDisplay.entries.firstOrNull { it.name == root.optString("definitionDisplay") }
                ?: DefinitionDisplay.BOTH
        )
        if (root.has("reminderMinutes")) {
            val minutes = root.getInt("reminderMinutes")
            settingsRepository.setReminderMinutes(minutes)
            ReminderScheduler.schedule(context, minutes)
        }
        // 启用词书集合按 sourceKey 重新定位 id；老备份只有单值 activeBookSourceKey 也兼容。
        // 没读到任何键 = 全部词书（空集合）。
        val activeKeys = root.optJSONArray("activeBookSourceKeys")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { k -> k.isNotBlank() } } }
            ?: root.optString("activeBookSourceKey").takeIf { it.isNotBlank() }?.let { listOf(it) }
            ?: emptyList()
        if (activeKeys.isNotEmpty()) {
            val booksNow = wordRepository.observeBooks().first()
            settingsRepository.setActiveBooks(
                activeKeys.mapNotNull { key ->
                    booksNow.firstOrNull { it.sourceKey == key }?.id
                }.toSet()
            )
        }
        // 备份来自老用户，恢复完不该再进首启引导
        settingsRepository.completeOnboarding()
        sessionStore.clear()
    }

    /**
     * 重置全部数据：清空学习状态、本地词书、错题集、搜索历史和设置，
     * 然后重新播种**默认词书**（内置的四级/高考，幂等）。
     * 顺带取消每日提醒闹钟——设置已回初始值。
     */
    suspend fun resetAllData() = withContext(Dispatchers.IO) {
        database.clearAllTables()
        settingsRepository.resetAll()
        sessionStore.clear()
        ReminderScheduler.schedule(context, null)
        seeder.ensureSeeded()
    }

    private fun wordBookFromJson(
        name: String,
        description: String,
        sourceKey: String,
        words: JSONArray
    ): WordBookSource {
        val entries = (0 until words.length()).mapNotNull { j ->
            val w = words.getJSONObject(j)
            val term = w.optString("term")
            if (term.isBlank()) {
                null
            } else {
                WordEntry(
                    term = term,
                    meaning = w.optString("meaning"),
                    phonetic = w.optString("phonetic").ifBlank { null },
                    example = w.optString("example").ifBlank { null },
                    exampleTranslation = w.optString("exampleTranslation").ifBlank { null }
                )
            }
        }
        return WordBookSource(name = name, description = description, sourceKey = sourceKey, entries = entries)
    }

    private fun writeDownload(name: String, bytes: ByteArray) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOAD_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("创建导出文件失败")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("写文件失败")
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
    }

    private fun readText(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: throw IllegalArgumentException("读不到文件")

    private fun downloadDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "vocab")

    companion object {
        const val KEY_FORMAT = "format"
        const val FORMAT_VERSION = 1
        const val DOWNLOAD_DIR = "Download/vocab/"

        private val STAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
