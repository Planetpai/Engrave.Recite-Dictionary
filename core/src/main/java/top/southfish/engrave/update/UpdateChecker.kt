package top.southfish.engrave.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查 GitHub Releases 上有没有新版本。点击检查时才联网，不自动检测。
 *
 * 仓库的 releases 按时间新→旧排列（含预发行版），遍历取第一条**带本端包**
 * （资产名匹配 Engrave-版本号-端别标签.apk）的 release：版本号从资产名提取
 * 而不是 tag——历史 tag 格式混乱不可靠。没带本端包的 release 跳过，继续看
 * 更早的；第一条带包的已经不比当前新，就认定已是最新。
 *
 * 端别标签（[assetTag]）随版本而异：
 * - 手机版：`android`
 * - 手表不带库版：`wearos-notrans`（ABI 拆分后资产名带 `-v7a`/`-v8a` 后缀，
 *   因无原生库两包内容相同，任一匹配即可）
 * - 手表带库版：`wearos-withtrans-v7a` / `wearos-withtrans-v8a`（按本机 ABI 精确取）
 *
 * 下载地址取该资产的 browser_download_url，由界面层 ACTION_VIEW 交给浏览器。
 */
class UpdateChecker(
    private val currentVersion: String,
    /** 端别标签：`android` / `wearos-notrans` / `wearos-withtrans-v8a` 等。 */
    private val assetTag: String
) {

    sealed interface Result {
        /** 当前已是最新（或 releases 里根本没有本端包）。 */
        object UpToDate : Result

        /** 有新版本：版本号、更新说明（release body，可能为空）、下载地址。 */
        data class UpdateAvailable(
            val version: String,
            val notes: String,
            val downloadUrl: String
        ) : Result

        /** 网络不通 / 接口异常：detail 带具体原因（HTTP 码或异常短标签），便于现场定位。 */
        data class NetworkError(val detail: String) : Result
    }

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val connection = runCatching { openConnection() }.getOrElse {
            return@withContext Result.NetworkError(it.shortLabel())
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                return@withContext Result.NetworkError("HTTP $code")
            }
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            parse(body)
        } catch (e: Exception) {
            Result.NetworkError(e.shortLabel())
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(): HttpURLConnection {
        val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        // GitHub API 要求带 UA；Accept 用 releases 预览的稳定版媒体类型
        connection.setRequestProperty("User-Agent", "Engrave-App")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return connection
    }

    private fun parse(json: String): Result {
        return runCatching {
            val releases = JSONArray(json)
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft", false)) continue
                val assets = release.optJSONArray("assets") ?: continue
                var version: String? = null
                var downloadUrl: String? = null
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val match = ASSET_PATTERN.matchEntire(asset.optString("name")) ?: continue
                    if (!matchesTag(match.groupValues[2])) continue
                    version = match.groupValues[1]
                    downloadUrl = asset.optString("browser_download_url")
                    break
                }
                if (version != null && downloadUrl != null) {
                    return if (isNewer(version, currentVersion)) {
                        Result.UpdateAvailable(
                            version = version,
                            notes = release.optString("body").trim(),
                            downloadUrl = downloadUrl
                        )
                    } else {
                        Result.UpToDate
                    }
                }
            }
            Result.UpToDate
        }.getOrElse { Result.NetworkError(it.shortLabel()) }
    }

    /**
     * 端别标签匹配：精确相等，或资产名在本端标签后还带后缀。
     * 后者是为不带库版准备的——ABI 拆分让它多出 `-v7a`/`-v8a` 尾巴，
     * 但两包内容相同，任一带尾资产都算本端包。
     */
    private fun matchesTag(candidate: String): Boolean =
        candidate == assetTag || candidate.startsWith("$assetTag-")

    companion object {
        /** 异常缩成一行短标签：类名 + 首行消息，够定位原因又不刷屏。 */
        private fun Throwable.shortLabel(): String {
            val msg = message?.lineSequence()?.firstOrNull()?.take(60)
            return if (msg.isNullOrBlank()) javaClass.simpleName
            else "${javaClass.simpleName}: $msg"
        }

        private const val RELEASES_URL =
            "https://api.github.com/repos/Planetpai/Engrave.Recite-Dictionary/releases"
        private const val TIMEOUT_MILLIS = 10_000

        /** Engrave-0.4beta.1-wearos-withtrans-v8a.apk → 版本号 + 端别标签。 */
        private val ASSET_PATTERN =
            Regex("""Engrave-(.+?)-((?:wearos|android)(?:-[a-z0-9]+)*)\.apk""")

        /** 一段版本号：数字开头可选字母后缀，如 "3"、"3beta"。 */
        private val SEGMENT_PATTERN = Regex("""(\d+)([a-z]*)""")

        internal fun isNewer(candidate: String, current: String): Boolean =
            compareVersion(candidate, current) > 0

        /**
         * 版本比较，兼容 "0.3beta.2" 这类混排：数字段按数值比，字母标记段
         * 按字母比（alpha < beta < rc），数字段永远大于标记段——所以
         * 0.3 正式版 > 0.3beta.9、0.3beta.2 > 0.3beta.1、0.4 > 0.3beta.9。
         * 某一边缺位按 0 补齐，"0.3" 因而大于 "0.3beta.x"。
         */
        internal fun compareVersion(a: String, b: String): Int {
            val pa = tokenize(a)
            val pb = tokenize(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x: Any = pa.getOrNull(i) ?: 0
                val y: Any = pb.getOrNull(i) ?: 0
                val c = when {
                    x is Int && y is Int -> x.compareTo(y)
                    x is Int -> 1
                    y is Int -> -1
                    else -> (x as String).compareTo(y as String)
                }
                if (c != 0) return c
            }
            return 0
        }

        /** "0.3beta.2" → [0, 3, "beta", 2]；解析不了的段直接丢掉。 */
        private fun tokenize(version: String): List<Any> {
            val out = mutableListOf<Any>()
            version.trim().removePrefix("v").lowercase().split('.').forEach { seg ->
                val m = SEGMENT_PATTERN.matchEntire(seg.trim()) ?: return@forEach
                out.add(m.groupValues[1].toInt())
                if (m.groupValues[2].isNotEmpty()) out.add(m.groupValues[2])
            }
            return out
        }
    }
}
