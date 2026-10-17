package top.southfish.engrave.translate

import android.content.Context

/**
 * 不带翻译版（noTrans）的引擎工厂。
 *
 * 本版本不引入 ONNX / ML Kit 依赖，搜索框也没有「长按进翻译页」入口，
 * 因此翻译页不可达、工厂永不调用。为满足共享层的符号需求仍提供同名函数，
 * 真被误调就显式报错（而不是返回一个假引擎，掩盖问题）。
 */
@Suppress("UNUSED_PARAMETER")
fun createTranslationEngine(
    context: Context,
    entry: TranslateModelEntry,
    direction: TranslateDirection
): TranslationEngine = error("本安装包不含离线翻译引擎")
