package top.southfish.engrave.translate

import android.content.Context

/**
 * 带翻译版（withTrans）的引擎工厂：按条目类型分发到 ONNX Marian 或 Google ML Kit。
 */
fun createTranslationEngine(
    context: Context,
    entry: TranslateModelEntry,
    direction: TranslateDirection
): TranslationEngine = when (entry.engine) {
    TranslateEngineType.ONNX -> OnnxMarianEngine(context, entry, direction)
    TranslateEngineType.ML_KIT -> MlKitEngine(direction)
}
