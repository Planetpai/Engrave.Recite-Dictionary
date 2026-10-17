package top.southfish.engrave.translate

/**
 * 整句翻译的基本词汇表：方向、引擎、档位、可选模型清单。
 *
 * 这里只放「常量与数据」，不放任何第三方依赖的代码——ONNX/MLKit 的实现分别在
 * `src/withTrans` 与 `src/noTrans` 两个源集里（见 [createTranslationEngine]），
 * 保证不带库版不会把原生库带进包。
 */

/** 翻译方向。输入什么语言由 [detectDirection] 自动判定，用户不需要手动选。 */
enum class TranslateDirection(val sourceLang: String, val targetLang: String) {
    /** 英译中。 */
    EN_ZH("en", "zh"),

    /** 中译英。 */
    ZH_EN("zh", "en");

    val displayName: String
        get() = when (this) {
            EN_ZH -> "英→中"
            ZH_EN -> "中→英"
        }
}

/**
 * 翻译引擎。
 *
 * - [ONNX]：Marian 模型 + DJL 分词器，全部自托管在本项目 GitHub Release，
 *   完全离线，不依赖 GMS。
 * - [ML_KIT]：Google ML Kit 翻译。质量好，但**模型由 Google 服务器下发**
 *   （约 30MB/方向）、**需要设备带 GMS**，且模型无法自托管。
 */
enum class TranslateEngineType(val displayName: String) {
    ONNX("ONNX（自托管离线）"),
    ML_KIT("Google ML Kit")
}

/** ONNX 模型的精度档位：量化省体积、全精度质量略好。 */
enum class TranslateTier(val displayName: String) {
    QUICK("量化版"),
    FULL("全精度版")
}

/**
 * 一条可选模型。
 *
 * [key] 同时充当安装目录名与偏好存储键，一经发布不可改。
 * [assetName] 指向本项目 GitHub Release 上的模型压缩包（见 [TranslateCatalog] 注释），
 * ML Kit 没有资产（模型由 Google 下发），因而是 null。
 */
data class TranslateModelEntry(
    val key: String,
    val engine: TranslateEngineType,
    /** ONNX 条目固定单一方向；ML Kit 自带双向，为 null。 */
    val direction: TranslateDirection?,
    /** ONNX 条目才有档位；ML Kit 为 null。 */
    val tier: TranslateTier?,
    val label: String,
    /** 展示用体积/说明文案。 */
    val sizeLabel: String,
    val assetName: String?,
    /** 需要下载的字节数；ML Kit 记为 0（不占我们带宽）。 */
    val bytes: Long
)

/**
 * 可选模型清单。
 *
 * **资产打包约定**（用户侧）：每个 ONNX 条目对应一个 zip，上传到本项目
 * GitHub Release，资产名即 [TranslateModelEntry.assetName]；zip 内**必须**恰好含
 * 下面 4 个文件（放在 zip 根目录，不要再套一层文件夹）：
 *
 * ```
 * encoder_model.onnx      // Xenova/opus-mt-<src>-<tgt> 的 onnx/encoder_model.onnx
 * decoder_model.onnx      // 同上，onnx/decoder_model.onnx（不要 merged 版）
 * tokenizer.json          // 同上，tokenizer.json
 * generation_config.json  // 同上，generation_config.json
 * ```
 *
 * 量化档取 `encoder_model_quantized.onnx` / `decoder_model_quantized.onnx`，
 * 打包时**改名为** `encoder_model.onnx` / `decoder_model.onnx`，
 * 这样 App 侧不用关心档位差别。
 *
 * 实测体积（Xenova/opus-mt-en-zh，逐文件字节数，来自 hf-mirror）：
 * - 量化档：encoder 50.4MiB + decoder 57.1MiB + tokenizer 6.1MiB ≈ **114MiB**
 * - 全精度：encoder 200.2MiB + decoder 224.7MiB + tokenizer 6.1MiB ≈ **431MiB**
 */
object TranslateCatalog {

    const val ML_KIT_KEY = "mlkit"

    /** ML Kit：不占包内资源，模型由 Google 在首次翻译时下发。 */
    val mlKit = TranslateModelEntry(
        key = ML_KIT_KEY,
        engine = TranslateEngineType.ML_KIT,
        direction = null,
        tier = null,
        label = "Google ML Kit",
        sizeLabel = "双向 · 模型由 Google 下发（约30MB/方向）",
        assetName = null,
        bytes = 0L
    )

    val onnxEnZhQuick = onnxEntry(TranslateDirection.EN_ZH, TranslateTier.QUICK, 119_500_000L)
    val onnxEnZhFull = onnxEntry(TranslateDirection.EN_ZH, TranslateTier.FULL, 452_000_000L)
    val onnxZhEnQuick = onnxEntry(TranslateDirection.ZH_EN, TranslateTier.QUICK, 119_500_000L)
    val onnxZhEnFull = onnxEntry(TranslateDirection.ZH_EN, TranslateTier.FULL, 452_000_000L)

    /** 全部条目（含预留的全精度档），供按 key 反查与安装状态统计。 */
    val all: List<TranslateModelEntry> =
        listOf(onnxEnZhQuick, onnxEnZhFull, onnxZhEnQuick, onnxZhEnFull, mlKit)

    /**
     * 界面实际展示的可选条目。**v0.4beta.1 暂不含全精度档**——fp32 单方向实测约 431MiB，
     * Release 未上传对应资产（用户 2026-10-17 拍板）。日后补传资产时，把两条 full 加回本列表即可，
     * 无需改其它代码。
     */
    val listed: List<TranslateModelEntry> =
        listOf(onnxEnZhQuick, onnxZhEnQuick, mlKit)

    /** 指定方向与档位的 ONNX 条目。 */
    fun onnx(direction: TranslateDirection, tier: TranslateTier): TranslateModelEntry =
        all.first { it.engine == TranslateEngineType.ONNX && it.direction == direction && it.tier == tier }

    /** 该条目的 ONNX 同档「另一方向」条目（英中的另一方向是中英）。 */
    fun onnxCounterpart(entry: TranslateModelEntry): TranslateModelEntry? {
        val dir = entry.direction ?: return null
        val tier = entry.tier ?: return null
        val other = if (dir == TranslateDirection.EN_ZH) TranslateDirection.ZH_EN else TranslateDirection.EN_ZH
        return onnx(other, tier)
    }

    private fun onnxEntry(
        direction: TranslateDirection,
        tier: TranslateTier,
        bytes: Long
    ): TranslateModelEntry {
        val dir = if (direction == TranslateDirection.EN_ZH) "en-zh" else "zh-en"
        val t = if (tier == TranslateTier.QUICK) "quick" else "full"
        val key = "onnx-$dir-$t"
        return TranslateModelEntry(
            key = key,
            engine = TranslateEngineType.ONNX,
            direction = direction,
            tier = tier,
            label = "${direction.displayName} · ${tier.displayName}",
            sizeLabel = "${direction.displayName} · 约 ${bytes / 1024 / 1024}MB",
            // 资产名以 Engrave- 开头，与 APK 资产同处一个 Release；
            // UpdateChecker 只认 .apk 结尾，不会误认模型包
            assetName = "Engrave-model-$key.zip",
            bytes = bytes
        )
    }

    /**
     * 按输入文本自动判定方向：含中日韩统一表意文字即认为中文，否则按英文。
     * 手表上输入以短句为主，这条规则足够稳；不做语言检测模型。
     */
    fun detectDirection(text: String): TranslateDirection =
        if (text.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }) {
            TranslateDirection.ZH_EN
        } else {
            TranslateDirection.EN_ZH
        }

    /** 安装 zip 内必须存在的文件名。 */
    val REQUIRED_FILES = listOf(
        "encoder_model.onnx",
        "decoder_model.onnx",
        "tokenizer.json",
        "generation_config.json"
    )
}
