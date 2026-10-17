package top.southfish.engrave.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google ML Kit 翻译引擎（可选）。
 *
 * 优点：质量好、双向免各自建模型；缺点：**需要设备带 GMS**，模型（约 30MB/方向）
 * 由 Google 服务器在首次使用时下发（不可自托管、不可离线从我们这边拿）。
 * 国内网络可能拉不到，失败信息直接透出给用户。
 *
 * 手表内存小：client 由调用方在离开页面时 [close]，不在后台常驻。
 */
class MlKitEngine(private val direction: TranslateDirection) : TranslationEngine {

    private var translator: Translator? = null

    override val describe: String
        get() = "Google ML Kit · ${direction.displayName}"

    override suspend fun translate(text: String, direction: TranslateDirection): String {
        val client = ensureTranslator()
        // 首次使用会下载语言模型，走 LOADING 态；之后命中本地缓存
        client.downloadModelIfNeeded().await()
        return client.translate(text).await()
    }

    override fun close() {
        runCatching { translator?.close() }
        translator = null
    }

    private fun ensureTranslator(): Translator {
        translator?.let { return it }
        val source = if (direction == TranslateDirection.ZH_EN) {
            TranslateLanguage.CHINESE
        } else {
            TranslateLanguage.ENGLISH
        }
        val target = if (direction == TranslateDirection.ZH_EN) {
            TranslateLanguage.ENGLISH
        } else {
            TranslateLanguage.CHINESE
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        return Translation.getClient(options).also { translator = it }
    }
}

/** 把 GMS [Task] 桥接成协程，避免额外引入 kotlinx-coroutines-play-services。 */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> cont.resume(result) }
    addOnFailureListener { error -> cont.resumeWithException(error) }
    addOnCanceledListener { cont.cancel() }
}
