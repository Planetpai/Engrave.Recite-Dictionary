package top.southfish.engrave.data.local

import java.time.Instant
import java.time.LocalDate
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

    fun of(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(epochMillis)
            .atZone(zone)
            .minusHours(ROLLOVER_HOUR.toLong())
            .toLocalDate()
            .toEpochDay()

    fun today(zone: ZoneId = ZoneId.systemDefault()): Long =
        of(System.currentTimeMillis(), zone)

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
