package top.southfish.engrave.data.seed

import android.content.Context
import top.southfish.engrave.domain.model.ImportResult
import top.southfish.engrave.domain.repository.SettingsRepository
import top.southfish.engrave.domain.repository.WordRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 首次启动时把 assets 里内置的词书导入数据库。
 *
 * 导入本身是按 sourceKey 幂等的，所以每次启动都可以无脑调用；
 * 已经导入过的词书会在事务开头就短路返回，代价只有一次索引查询。
 *
 * 以后要加新词书，往 assets/wordbooks/ 丢一个 JSON 即可，启动时会自动枚举导入，
 * 不用改任何代码逻辑。
 *
 * 下架/合并内置词书也不用改代码：启动时会把数据库里「sourceKey 以
 * wordbooks/ 开头、但 assets 里已不存在」的词书顺手删掉（外键级联带走
 * 词条和调度状态）；如果用户当前正选着被删的书，背诵词书重置为「全部词书」。
 */
class SeedDataProvider(
    private val context: Context,
    private val wordRepository: WordRepository,
    private val settingsRepository: SettingsRepository
) {

    suspend fun ensureSeeded(): List<ImportResult> = withContext(Dispatchers.IO) {
        val assets = seedAssets()
        val results = assets.mapNotNull { assetPath ->
            val json = readAsset(assetPath) ?: return@mapNotNull null
            val source = WordBookJsonParser.parse(sourceKey = assetPath, json = json)
                ?: return@mapNotNull null
            wordRepository.importBook(source)
        }
        retireMissingBuiltInBooks(assets.toSet())
        results
    }

    /**
     * 内置词书就是 assets/wordbooks/ 里的全部 JSON（人教版课本单元书），
     * 运行时直接枚举目录，新增词书丢文件即可，无需再改代码。
     * 排序保证导入顺序稳定（年级/单元编号升序）。
     */
    private fun seedAssets(): List<String> =
        runCatching {
            context.assets.list(ASSET_DIR)
                ?.filter { it.endsWith(".json") }
                ?.sorted()
                ?.map { "$ASSET_DIR/$it" }
        }.getOrNull().orEmpty()

    /**
     * 自动下架：数据库里凡是「随包内置」（sourceKey 以 wordbooks/ 开头）、
     * 但当前安装包 assets 里已经不存在的词书，一律删掉（外键级联带走词条
     * 和调度状态）。这样替换/合并内置词书后，存量安装启动即清理干净，
     * 不用再手动维护退役清单；用户自己导入的词书 sourceKey 不带这个前缀，
     * 永远不会被碰。
     */
    private suspend fun retireMissingBuiltInBooks(activeAssetKeys: Set<String>) {
        val activeBookIds = settingsRepository.queueConfig.first().activeBookIds
        val retiredIds = mutableSetOf<Long>()
        wordRepository.getBooksSnapshot().forEach { book ->
            val key = book.sourceKey ?: return@forEach
            if (key.startsWith("$ASSET_DIR/") && key !in activeAssetKeys) {
                wordRepository.deleteBook(book.id)
                retiredIds += book.id
            }
        }
        // 启用集合里的书被下架：从多选集合里剔除（剔空了自然回落「全部词书」）
        if (retiredIds.any { it in activeBookIds }) {
            settingsRepository.setActiveBooks(activeBookIds - retiredIds)
        }
    }

    private fun readAsset(path: String): String? = runCatching {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    companion object {
        private const val ASSET_DIR = "wordbooks"
    }
}
