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
 */
class TtsSpeaker(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    @Synchronized
    fun speak(text: String) {
        if (tts == null) {
            pending = text
            tts = TextToSpeech(appContext) { status ->
                synchronized(this@TtsSpeaker) {
                    ready = status == TextToSpeech.SUCCESS
                    if (ready) {
                        // 词条都是英文；缺美音数据时退回通用英语
                        val lang = tts?.setLanguage(Locale.US)
                        if (lang == TextToSpeech.LANG_MISSING_DATA ||
                            lang == TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            tts?.setLanguage(Locale.ENGLISH)
                        }
                        pending?.let { speakInternal(it) }
                        pending = null
                    }
                }
            }
            return
        }
        if (ready) speakInternal(text) else pending = text
    }

    private fun speakInternal(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private companion object {
        const val UTTERANCE_ID = "word"
    }
}
