package top.southfish.engrave.data.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 系统 TTS 朗读（单词发音）。
 *
 * 引擎连接开销大且异步（onInit 回调后才算就绪），所以做成进程级单例懒连：
 * 第一次 speak 时才绑定引擎，绑定前的朗读请求缓存一条、就绪后补播。
 * 不主动 shutdown——手表应用进程随用随起，引擎绑定随进程回收即可。
 *
 * [checkAvailable] 供设置页做可用性探测（复用同一条引擎连接），系统缺英文
 * 语音数据时返回 false。结果随引擎生命周期缓存；用户装好语音包后重开
 * 应用（新进程）即生效。
 */
class TtsSpeaker(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    /** 引擎就绪后英语（美音/通用英语回退）是否真的能用，缓存 setLanguage 的实测结果 */
    private var englishOk = false
    private var pending: String? = null

    /** 引擎绑定期间挂起的可用性检测（同一时刻最多一个，后来的覆盖先前的）。 */
    private var pendingCheck: ((Boolean) -> Unit)? = null

    /** 异步检测英文 TTS 是否可用；结果在引擎回调线程上回调，注意切线程。 */
    @Synchronized
    fun checkAvailable(onResult: (Boolean) -> Unit) {
        if (tts == null) {
            pendingCheck = onResult
            tts = TextToSpeech(appContext) { status -> onInit(status) }
            return
        }
        if (ready) {
            onResult(englishOk)
        } else {
            // 引擎正在绑定：挂起本次检测，onInit 就绪后一并回调
            pendingCheck = onResult
        }
    }

    @Synchronized
    fun speak(text: String) {
        if (tts == null) {
            pending = text
            tts = TextToSpeech(appContext) { status -> onInit(status) }
            return
        }
        if (ready) speakInternal(text) else pending = text
    }

    private fun onInit(status: Int) {
        synchronized(this@TtsSpeaker) {
            ready = status == TextToSpeech.SUCCESS
            // 直接用 setLanguage 的返回值判定，而不是 isLanguageAvailable：
            // 部分引擎对 isLanguageAvailable 返回 LANG_AVAILABLE(0) 但语音
            // 实际可用，而 setLanguage 的结果与朗读真正走的路径一致。
            englishOk = ready && configureEnglish()
            pendingCheck?.invoke(ready && englishOk)
            pendingCheck = null
            if (ready) {
                pending?.let { speakInternal(it) }
                pending = null
            }
        }
    }

    /**
     * 把引擎语言设为英语：先试美音，缺数据/不支持再退通用英语。
     * 任一档 setLanguage 返回 >= LANG_AVAILABLE(0) 就算可用。
     */
    private fun configureEnglish(): Boolean {
        val engine = tts ?: return false
        if (engine.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE) return true
        return engine.setLanguage(Locale.ENGLISH) >= TextToSpeech.LANG_AVAILABLE
    }

    private fun speakInternal(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private companion object {
        const val UTTERANCE_ID = "word"
    }
}
