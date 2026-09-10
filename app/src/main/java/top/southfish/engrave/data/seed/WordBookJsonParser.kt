package top.southfish.engrave.data.seed

import top.southfish.engrave.domain.model.WordBookSource
import top.southfish.engrave.domain.model.WordEntry
import org.json.JSONObject

/**
 * 解析词书 JSON。用 Android 内置的 org.json 而不是 kotlinx-serialization，
 * 省掉一个序列化插件和它的编译期开销——词书解析只在导入时跑一次。
 *
 * 格式：
 * ```json
 * {
 *   "name": "四级核心词",
 *   "description": "…",
 *   "words": [
 *     { "term": "abandon", "phonetic": "/əˈbændən/",
 *       "meaning": "v. 放弃；抛弃",
 *       "example": "He abandoned the plan.",
 *       "exampleTranslation": "他放弃了这个计划。" }
 *   ]
 * }
 * ```
 *
 * 词书数据可能来自任何地方，所以解析要对脏数据宽容：
 * 缺字段就跳过该条，整体解析失败返回 null，绝不抛异常搞崩启动流程。
 */
object WordBookJsonParser {

    fun parse(sourceKey: String, json: String): WordBookSource? = runCatching {
        val root = JSONObject(json)
        val wordsArray = root.optJSONArray("words") ?: return@runCatching null

        val entries = ArrayList<WordEntry>(wordsArray.length())
        for (i in 0 until wordsArray.length()) {
            val item = wordsArray.optJSONObject(i) ?: continue
            val term = item.stringOrNull("term") ?: continue
            val meaning = item.stringOrNull("meaning") ?: continue
            entries += WordEntry(
                term = term,
                meaning = meaning,
                phonetic = item.stringOrNull("phonetic"),
                example = item.stringOrNull("example"),
                exampleTranslation = item.stringOrNull("exampleTranslation")
            )
        }

        if (entries.isEmpty()) {
            null
        } else {
            WordBookSource(
                name = root.stringOrNull("name") ?: sourceKey,
                description = root.stringOrNull("description") ?: "",
                sourceKey = sourceKey,
                entries = entries
            )
        }
    }.getOrNull()

    /**
     * org.json 的 optString 在键缺失时返回空串，但值是 JSON null 时会返回字符串 "null"，
     * 所以先用 isNull 挡一道。
     */
    private fun JSONObject.stringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key, "").trim().ifEmpty { null }
    }
}
