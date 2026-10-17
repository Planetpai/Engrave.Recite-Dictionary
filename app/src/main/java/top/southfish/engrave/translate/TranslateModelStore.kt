package top.southfish.engrave.translate

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 翻译模型的本地存储与获取。
 *
 * 模型不随包，放在**本项目 GitHub Release** 上；下载到应用私有目录
 * (`filesDir/translate/<key>/`)，卸载即清。同时提供「手动导入」兜底，
 * 应对国内访问 GitHub 不畅的情况（用户可自行从 hf-mirror 取文件打成 zip 导入）。
 *
 * 网络写法与 [top.southfish.engrave.update.UpdateChecker] 一致（HttpURLConnection +
 * 短超时 + GitHub 要求的 UA），不引入额外网络库。
 */
class TranslateModelStore(private val context: Context) {

    /** 所有模型的根目录。 */
    private val root: File
        get() = File(context.filesDir, "translate")

    /** 条目对应的安装目录。 */
    fun modelDir(entry: TranslateModelEntry): File = File(root, entry.key)

    /**
     * 是否已就绪。ML Kit 的模型由 Google 服务器管理，本地无包，恒为 true
     * （真正的就绪判断交给 ML Kit 运行时）。
     */
    fun isInstalled(entry: TranslateModelEntry): Boolean {
        if (entry.engine != TranslateEngineType.ONNX) return true
        val dir = modelDir(entry)
        return TranslateCatalog.REQUIRED_FILES.all { name ->
            val f = File(dir, name)
            f.isFile && f.length() > 0
        }
    }

    /** 已安装的 ONNX 条目 key 集合，供界面显示下载状态。 */
    fun installedKeys(): Set<String> =
        TranslateCatalog.all
            .filter { it.engine == TranslateEngineType.ONNX && isInstalled(it) }
            .map { it.key }
            .toSet()

    /** 删除条目已下载的模型（ML Kit 无本地包，不做任何事）。 */
    fun delete(entry: TranslateModelEntry) {
        if (entry.engine != TranslateEngineType.ONNX) return
        modelDir(entry).deleteRecursively()
    }

    /**
     * 从本项目 GitHub Release 下载并解包。
     *
     * [onProgress] 传 0f..1f；无法确定总量时传负值表示不确定进度。
     * 已存在同名条目目录时先整体清掉，保证不会残留半包。
     */
    suspend fun download(
        entry: TranslateModelEntry,
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val assetName = entry.assetName
            ?: return@withContext Result.failure(IllegalStateException("该条目没有可下载资产"))
        val url = runCatching { resolveAssetUrl(assetName) }.getOrElse {
            return@withContext Result.failure(it)
        } ?: return@withContext Result.failure(IllegalStateException("Release 里没有找到 $assetName"))

        val tmp = File(context.cacheDir, "${entry.key}.zip.part")
        runCatching {
            tmp.delete()
            downloadToFile(url, tmp, onProgress)
            installFrom(tmp, entry)
        }.onFailure { tmp.delete() }.map { }
    }

    /**
     * 手动导入兜底：用户从设备上选一个 zip（内容约定同 [TranslateCatalog]）。
     * 不支持确定进度（SAF 通常不给长度），[onProgress] 传负值。
     */
    suspend fun importFromZip(
        entry: TranslateModelEntry,
        uri: Uri,
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(-1f)
            val stream = context.contentResolver.openInputStream(uri)
                ?: error("打不开所选文件")
            stream.use {
                val tmp = File(context.cacheDir, "${entry.key}.import.zip")
                tmp.delete()
                tmp.outputStream().use { out -> it.copyTo(out) }
                try {
                    installFrom(tmp, entry)
                } finally {
                    tmp.delete()
                }
            }
        }.onSuccess { onProgress(1f) }
    }

    // ---- 内部实现 ----

    /**
     * 解包到条目目录：先解到临时目录，校验四个必需文件都在，再整体替换。
     * 只按文件名取，忽略 zip 里的目录层级（允许打平也允许套一层），
     * 因此天然没有 zip-slip 路径穿越问题。
     */
    private fun installFrom(zipFile: File, entry: TranslateModelEntry) {
        val stage = File(root, "${entry.key}.tmp")
        stage.deleteRecursively()
        if (!stage.mkdirs()) error("无法创建模型目录")

        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val name = File(e.name).name
                if (!e.isDirectory && name in TranslateCatalog.REQUIRED_FILES) {
                    File(stage, name).outputStream().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }

        val missing = TranslateCatalog.REQUIRED_FILES.filter { !File(stage, it).isFile }
        if (missing.isNotEmpty()) {
            stage.deleteRecursively()
            error("压缩包缺少文件：${missing.joinToString("、")}")
        }

        val dest = modelDir(entry)
        dest.deleteRecursively()
        if (!stage.renameTo(dest)) {
            // renameTo 跨不了文件系统时退化为拷贝（这里同盘，通常走不到）
            stage.copyRecursively(dest, overwrite = true)
            stage.deleteRecursively()
        }
    }

    private fun downloadToFile(url: String, dest: File, onProgress: (Float) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val total = connection.contentLengthLong
            var read = 0L
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onProgress(if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else -1f)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 在 releases 列表里找资产名精确匹配的条目，取 browser_download_url。 */
    private fun resolveAssetUrl(assetName: String): String? {
        val connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val body: String = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val releases = JSONArray(body)
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft", false)) continue
                val assets = release.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    if (asset.optString("name") == assetName) {
                        return asset.optString("browser_download_url").ifBlank { null }
                    }
                }
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val RELEASES_URL =
            "https://api.github.com/repos/Planetpai/Engrave.Recite-Dictionary/releases"
        const val TIMEOUT_MILLIS = 15_000
        const val USER_AGENT = "Engrave-App"
    }
}
