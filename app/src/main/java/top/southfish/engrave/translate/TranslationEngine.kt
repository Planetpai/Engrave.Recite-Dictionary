package top.southfish.engrave.translate

import android.content.Context

/**
 * 翻译引擎契约。共享层只认这个接口，具体实现由构建版本提供：
 *
 * - `src/withTrans`：ONNX Marian（+DJL 分词器）与 Google ML Kit 两套
 * - `src/noTrans`：只有 ML Kit 不可用的空实现 → [createTranslationEngine] 抛异常
 *
 * 生命周期由调用方（ViewModel）掌握：**进翻译页才创建、离开页面即 [close]**。
 * 手表内存紧张，模型（ONNX session / ML Kit client）不允许跨页常驻。
 */
interface TranslationEngine {

    /**
     * 翻译整句。[direction] 决定中英方向（调用方已按输入自动判定）。
     * 实现必须自行切到后台线程，不要在调用线程上做推理。
     */
    suspend fun translate(text: String, direction: TranslateDirection): String

    /** 就绪描述，界面灰字展示用（如 "Marian · 量化版"）。 */
    val describe: String

    /** 释放原生资源。重复调用必须无害。 */
    fun close()
}

/**
 * 按条目创建引擎。
 *
 * [entry] 为 ML Kit 条目时 [direction] 决定 ML Kit 客户端的语言对；
 * 为 ONNX 条目时必须与 [entry] 自身方向一致。
 *
 * **该函数不在这里声明**：`src/main` 与 flavor 源集属于同一次 Kotlin 编译，
 * 由 `src/withTrans` 与 `src/noTrans` 各自提供一份同名顶层函数即可，
 * 不需要 expect/actual（单平台工程不支持）。
 * 约定签名：
 *
 * ```kotlin
 * fun createTranslationEngine(
 *     context: Context,
 *     entry: TranslateModelEntry,
 *     direction: TranslateDirection
 * ): TranslationEngine
 * ```
 */
