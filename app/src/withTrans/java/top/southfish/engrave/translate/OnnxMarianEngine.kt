package top.southfish.engrave.translate

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import kotlin.coroutines.coroutineContext

/**
 * 自托管离线翻译引擎：Marian 编码器/解码器 ONNX + DJL 分词器。
 *
 * 模型资产与打包约定见 [TranslateCatalog]；模型本身不在 APK 里，运行时从
 * `filesDir/translate/<key>/` 读。
 *
 * **解码策略**：用**非缓存**的 `decoder_model.onnx`，每步喂完整的 decoder 输入
 * 序列、只取最后一个位置的 logits 做贪心（greedy），不用 KV cache / merged 图。
 * 结果是等价的，换来的是实现简单、对模型导出形态不挑（引擎启动不了的概率低）。
 * 代价是长句 O(n²) 的重复前向——手表上句长有限，用户已确认不设硬指标。
 *
 * **输入名自适应**：不同导出（含/不含 `encoder_attention_mask`）输入名不完全一致，
 * 这里按 `OrtSession.inputNames` 动态装配，只喂图里真正存在的输入。
 */
class OnnxMarianEngine(
    context: Context,
    private val entry: TranslateModelEntry,
    private val direction: TranslateDirection
) : TranslationEngine {

    private val dir: File = TranslateModelStore(context).modelDir(entry)

    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var tokenizer: HuggingFaceTokenizer? = null

    private var startTokenId = 0L
    private var eosTokenId = 0L
    private var maxNewTokens = HARD_TOKEN_CAP

    override val describe: String
        get() = "Marian · ${direction.displayName}" +
            (entry.tier?.let { " · ${it.displayName}" } ?: "")

    override suspend fun translate(text: String, direction: TranslateDirection): String =
        withContext(Dispatchers.IO) {
            ensureLoaded()
            greedyDecode(text)
        }

    override fun close() {
        runCatching { decoder?.close() }
        runCatching { encoder?.close() }
        runCatching { tokenizer?.close() }
        decoder = null
        encoder = null
        tokenizer = null
    }

    // ---- 内部实现 ----

    private fun ensureLoaded() {
        if (decoder != null) return

        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()

        encoder = env.createSession(File(dir, "encoder_model.onnx").absolutePath, options)
        decoder = env.createSession(File(dir, "decoder_model.onnx").absolutePath, options)
        tokenizer = HuggingFaceTokenizer.newInstance(File(dir, "tokenizer.json").toPath())

        val cfg = JSONObject(File(dir, "generation_config.json").readText())
        startTokenId = cfg.optLong("decoder_start_token_id", cfg.optLong("bos_token_id", 0L))
        eosTokenId = cfg.optLong("eos_token_id", 0L)
        maxNewTokens = cfg.optInt("max_length", HARD_TOKEN_CAP).coerceIn(1, HARD_TOKEN_CAP)
    }

    private suspend fun greedyDecode(text: String): String {
        val encoderSession = encoder ?: error("编码器未加载")
        val decoderSession = decoder ?: error("解码器未加载")
        val tok = tokenizer ?: error("分词器未加载")
        val env = OrtEnvironment.getEnvironment()

        val encoding = tok.encode(text)
        val srcIds = encoding.ids
        val srcMask = encoding.attentionMask
            ?: LongArray(srcIds.size) { 1L }

        val srcIdsTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(srcIds), longArrayOf(1, srcIds.size.toLong())
        )
        val srcMaskTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(srcMask), longArrayOf(1, srcMask.size.toLong())
        )

        return srcIdsTensor.use { idsTensor ->
            srcMaskTensor.use { maskTensor ->
                // 编码器：只喂图里存在的输入（input_ids / attention_mask）
                val encoderInputs = LinkedHashMap<String, OnnxTensorLike>()
                for (name in encoderSession.inputNames) {
                    encoderInputs[name] = when (name) {
                        "input_ids", "input_id" -> idsTensor
                        "attention_mask" -> maskTensor
                        else -> error("编码器有未支持的输入：$name")
                    }
                }

                encoderSession.run(encoderInputs).use { encoderResult ->
                    val hiddenName = encoderSession.outputNames.firstOrNull { it == "last_hidden_state" }
                        ?: encoderSession.outputNames.first()
                    val hidden = encoderResult.get(hiddenName).orElse(null) as? OnnxTensor
                        ?: error("编码器输出 $hiddenName 不是张量")

                    decodeLoop(env, decoderSession, tok, maskTensor, hidden)
                }
            }
        }
    }

    private suspend fun decodeLoop(
        env: OrtEnvironment,
        decoderSession: OrtSession,
        tok: HuggingFaceTokenizer,
        srcMaskTensor: OnnxTensor,
        hidden: OnnxTensor
    ): String {
        val output = ArrayList<Long>()
        var seq = longArrayOf(startTokenId)
        var finished = false

        for (step in 0 until maxNewTokens) {
            coroutineContext.ensureActive()

            val decIdsTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(seq), longArrayOf(1, seq.size.toLong())
            )
            try {
                val decoderInputs = LinkedHashMap<String, OnnxTensorLike>()
                for (name in decoderSession.inputNames) {
                    decoderInputs[name] = when (name) {
                        "input_ids", "input_id" -> decIdsTensor
                        "encoder_attention_mask" -> srcMaskTensor
                        "encoder_hidden_states", "encoder_last_hidden_state" -> hidden
                        else -> error("解码器有未支持的输入：$name")
                    }
                }

                decoderSession.run(decoderInputs).use { decoderResult ->
                    val logitsName = decoderSession.outputNames.firstOrNull { it == "logits" }
                        ?: decoderSession.outputNames.first()
                    val logits = decoderResult.get(logitsName).orElse(null) as? OnnxTensor
                        ?: error("解码器输出 $logitsName 不是张量")
                    val next = argmaxLastPosition(logits, seq.size)
                    if (next == eosTokenId) {
                        finished = true  // 遇句尾符，本步生成结束
                    } else {
                        output.add(next)
                        seq = seq + next
                    }
                }
            } finally {
                decIdsTensor.close()
            }
            if (finished) break
        }

        return tok.decode(output.toLongArray(), true).trim()
    }

    /** 取 [seqLen, vocab]（batch=1 打平）最后一行的 argmax。 */
    private fun argmaxLastPosition(logits: OnnxTensor, seqLen: Int): Long {
        val buffer = logits.floatBuffer
        val vocab = buffer.limit() / seqLen
        val offset = (seqLen - 1) * vocab
        var best = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in 0 until vocab) {
            val v = buffer.get(offset + i)
            if (v > bestValue) {
                bestValue = v
                best = i
            }
        }
        return best.toLong()
    }

    private companion object {
        /**
         * 单次翻译最多新生成的 token 数。generation_config 里通常写 512，
         * 但手表上 512 步全序列重算太慢，这里再收紧一道；句长正常够用。
         */
        const val HARD_TOKEN_CAP = 256
    }
}
