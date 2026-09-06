package com.example.vocabwatch.data.seed

import android.content.Context
import com.example.vocabwatch.domain.model.ImportResult
import com.example.vocabwatch.domain.repository.WordRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 首次启动时把 assets 里内置的词书导入数据库。
 *
 * 导入本身是按 sourceKey 幂等的，所以每次启动都可以无脑调用；
 * 已经导入过的词书会在事务开头就短路返回，代价只有一次索引查询。
 *
 * 以后要加新词书，往 assets/wordbooks/ 丢一个 JSON 再登记到 [ASSETS] 即可，
 * 不用改任何代码逻辑。
 */
class SeedDataProvider(
    private val context: Context,
    private val wordRepository: WordRepository
) {

    suspend fun ensureSeeded(): List<ImportResult> = withContext(Dispatchers.IO) {
        ASSETS.mapNotNull { assetPath ->
            val json = readAsset(assetPath) ?: return@mapNotNull null
            val source = WordBookJsonParser.parse(sourceKey = assetPath, json = json)
                ?: return@mapNotNull null
            wordRepository.importBook(source)
        }
    }

    private fun readAsset(path: String): String? = runCatching {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    companion object {
        val ASSETS = listOf(
            "wordbooks/cet4-core.json",
            "wordbooks/gk-core.json"
        )
    }
}
