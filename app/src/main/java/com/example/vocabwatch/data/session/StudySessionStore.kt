package com.example.vocabwatch.data.session

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 学习会话的断点续传。
 *
 * 每次评分虽然都落了库，但「今天这轮还剩哪些卡、刷到第几遍」只活在内存里：
 * 中途退出再进来，按当日额度重算会得到空队列，于是出现「明明没背完却
 * 提示今天学完了」。这里把队列快照写成 filesDir 下的 JSON，StudyViewModel
 * 每次发射状态时保存，新一轮会话开始时优先恢复。
 *
 * 快照只在本日有效（跨天作废）；撤销栈跨会话不保留。
 */
class StudySessionStore(dir: File) {

    private val file: File = File(dir, FILE_NAME)

    data class Snapshot(
        /** 保存当天的 epochDay，隔天的快照直接丢弃。 */
        val day: Long,
        /** BROWSING / FORMAL / REVIEW / BREAK_FORMAL / BREAK_REVIEW */
        val phase: String,
        val browseQueue: List<Long>,
        val selected: List<Long>,
        val selectionTarget: Int,
        val testQueue: List<CardSnapshot>,
        /** 还没进复习阶段的到期词，背完新词后要用。 */
        val dueItems: List<Long>,
        val newWords: List<Long>,
        val reviewWords: List<Long>,
        val againCount: Int,
        /** BREAK_* 阶段的提示文案。 */
        val breakTitle: String? = null,
        val breakSubtitle: String? = null
    ) {
        data class CardSnapshot(val wordId: Long, val encounter: Int, val flipped: Boolean)

        /** 空队列的快照没有恢复价值。 */
        val hasContent: Boolean
            get() = browseQueue.isNotEmpty() || selected.isNotEmpty() || testQueue.isNotEmpty() ||
                dueItems.isNotEmpty() || newWords.isNotEmpty() || reviewWords.isNotEmpty()
    }

    /**
     * 读取快照；文件缺失或损坏返回 null。
     * 不做跨天作废：夜里背到一半、第二天早上接着背是正常用法，
     * 未完成的词下次恢复时带着数据库里的最新调度状态继续，语义正确。
     */
    fun load(): Snapshot? = runCatching {
        if (!file.exists()) return null
        val root = JSONObject(file.readText())
        val snapshot = Snapshot(
            day = root.getLong("day"),
            phase = root.getString("phase"),
            browseQueue = root.getJSONArray("browseQueue").longs(),
            selected = root.getJSONArray("selected").longs(),
            selectionTarget = root.optInt("selectionTarget", 0),
            testQueue = root.getJSONArray("testQueue").let { arr ->
                (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    Snapshot.CardSnapshot(
                        wordId = c.getLong("id"),
                        encounter = c.optInt("encounter", 1),
                        flipped = c.optBoolean("flipped", false)
                    )
                }
            },
            dueItems = root.optJSONArray("dueItems")?.longs().orEmpty(),
            newWords = root.optJSONArray("newWords")?.longs().orEmpty(),
            reviewWords = root.optJSONArray("reviewWords")?.longs().orEmpty(),
            againCount = root.optInt("againCount", 0),
            breakTitle = root.optString("breakTitle").ifBlank { null },
            breakSubtitle = root.optString("breakSubtitle").ifBlank { null }
        )
        snapshot
    }.getOrNull()

    fun save(snapshot: Snapshot) {
        runCatching {
            val root = JSONObject().apply {
                put("day", snapshot.day)
                put("phase", snapshot.phase)
                put("browseQueue", JSONArray(snapshot.browseQueue))
                put("selected", JSONArray(snapshot.selected))
                put("selectionTarget", snapshot.selectionTarget)
                put("testQueue", JSONArray().apply {
                    snapshot.testQueue.forEach {
                        put(JSONObject().apply {
                            put("id", it.wordId)
                            put("encounter", it.encounter)
                            put("flipped", it.flipped)
                        })
                    }
                })
                put("dueItems", JSONArray(snapshot.dueItems))
                put("newWords", JSONArray(snapshot.newWords))
                put("reviewWords", JSONArray(snapshot.reviewWords))
                put("againCount", snapshot.againCount)
                snapshot.breakTitle?.let { put("breakTitle", it) }
                snapshot.breakSubtitle?.let { put("breakSubtitle", it) }
            }
            file.writeText(root.toString())
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private fun JSONArray.longs(): List<Long> = (0 until length()).map { getLong(it) }

    companion object {
        const val FILE_NAME = "study_session.json"
    }
}
