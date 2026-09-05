package com.example.vocabwatch.ui.components

import com.example.vocabwatch.domain.srs.ReviewOutcome
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 把间隔天数渲染成手表上看得懂的短标签。
 *
 * 屏幕只有 450px 宽，所以必须分级取整：分钟不显示小数，
 * 天在 10 天以内保留一位，再往上就取整，避免出现「12.3456 天后」。
 */
fun formatInterval(days: Double): String {
    if (days <= 0.0) return "现在"
    if (days < 1.0) {
        val minutes = (days * 1440).roundToInt()
        return if (minutes <= 1) "1 分钟" else "$minutes 分钟"
    }
    return if (days < 10.0) {
        val rounded = (days * 10).roundToInt() / 10.0
        if (abs(rounded - rounded.toLong()) < 0.05) "${rounded.toLong()} 天" else "$rounded 天"
    } else {
        "${days.roundToInt()} 天"
    }
}

fun formatInterval(outcome: ReviewOutcome): String = formatInterval(outcome.scheduledInDays)

/** 学习进度，例如「3/20」。总数为 0 时返回空串，避免显示「0/0」。 */
fun formatProgress(current: Int, total: Int): String =
    if (total <= 0) "" else "$current/$total"

/** 正确率百分比，null 表示今天还没有数据。 */
fun formatRate(rate: Double?): String =
    if (rate == null) "—" else "${(rate * 100).roundToInt()}%"
