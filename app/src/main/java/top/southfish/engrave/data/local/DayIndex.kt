package top.southfish.engrave.data.local

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 把时间戳折算成「学习日」的序号（epochDay）。
 *
 * 翻天时刻设为凌晨 4 点：晚上 11 点到凌晨 1 点的连续学习应该算同一天，
 * 否则熬夜背单词会把连续天数打断。这和 Anki 的默认行为一致。
 *
 * 用的是系统默认时区。跨国旅行时时区变化会让当天的边界移动一次，
 * 对背单词场景可以接受。
 */
object DayIndex {

    const val ROLLOVER_HOUR = 4
    private const val MILLIS_PER_DAY = 86_400_000L

    fun of(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(epochMillis)
            .atZone(zone)
            .minusHours(ROLLOVER_HOUR.toLong())
            .toLocalDate()
            .toEpochDay()

    fun today(zone: ZoneId = ZoneId.systemDefault()): Long =
        of(System.currentTimeMillis(), zone)

    /**
     * 当前学习日的起点（今天凌晨 [ROLLOVER_HOUR] 点；凌晨 4 点前则是昨天的该时刻）。
     * 跨日逾期的学习卡用它做分界：dueAt 早于它 = 这张学习步卡片已经隔了一夜，
     * 不再要求用户补走 10 分钟/1 小时步骤，直接毕业进当天复习队列。
     */
    fun rolloverStart(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        nextRollover(epochMillis, zone) - MILLIS_PER_DAY

    /**
     * 下一个凌晨 [ROLLOVER_HOUR] 点的时间戳。
     * 如果当前时间已过今天的该时刻，返回明天的该时刻。
     * 供「推迟到明天」类操作使用，和调度器的天级到期锚点保持一致。
     */
    fun nextRollover(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        val today = ldt.toLocalDate().atTime(ROLLOVER_HOUR, 0)
        return if (ldt.isBefore(today)) {
            today.atZone(zone).toInstant().toEpochMilli()
        } else {
            today.plusDays(1).atZone(zone).toInstant().toEpochMilli()
        }
    }

    /**
     * 从今天往回数连续学习了多少天。
     * 今天还没学不打断连续记录，所以退一天再开始数。
     */
    fun streakFrom(reviewDays: Collection<Long>, today: Long): Int {
        if (reviewDays.isEmpty()) return 0
        val days = reviewDays.toHashSet()
        var cursor = if (days.contains(today)) today else today - 1
        var streak = 0
        while (days.contains(cursor)) {
            streak++
            cursor--
        }
        return streak
    }
}
