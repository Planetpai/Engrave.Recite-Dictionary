package com.example.vocabwatch.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DayIndexTest {

    private val today = 20_000L

    @Test
    fun `没有学习记录 - 连续天数为 0`() {
        assertEquals(0, DayIndex.streakFrom(emptyList(), today))
    }

    @Test
    fun `只学了今天 - 连续 1 天`() {
        assertEquals(1, DayIndex.streakFrom(listOf(today), today))
    }

    @Test
    fun `连续三天含今天 - 连续 3 天`() {
        assertEquals(
            3,
            DayIndex.streakFrom(listOf(today, today - 1, today - 2), today)
        )
    }

    @Test
    fun `今天还没学但昨天学了 - 不打断连续记录`() {
        assertEquals(
            2,
            DayIndex.streakFrom(listOf(today - 1, today - 2), today)
        )
    }

    @Test
    fun `断在两天前 - 连续天数归零`() {
        assertEquals(0, DayIndex.streakFrom(listOf(today - 2), today))
        assertEquals(0, DayIndex.streakFrom(listOf(today - 5, today - 6), today))
    }

    @Test
    fun `中间缺一天 - 只数到缺口为止`() {
        assertEquals(
            1,
            DayIndex.streakFrom(listOf(today, today - 2, today - 3), today)
        )
    }

    @Test
    fun `输入有重复和无序 - 结果仍然正确`() {
        assertEquals(
            3,
            DayIndex.streakFrom(
                listOf(today - 2, today, today - 1, today, today - 2),
                today
            )
        )
    }

    @Test
    fun `凌晨 4 点前算前一天 - 熬夜学习不会中断连续记录`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val day = LocalDate.of(2026, 9, 5)

        // 当天 03:00 与前一天 23:00 属于同一个学习日
        val threeAm = millis(day.atTime(3, 0), zone)
        val latePreviousNight = millis(day.minusDays(1).atTime(23, 0), zone)
        assertEquals(
            DayIndex.of(latePreviousNight, zone),
            DayIndex.of(threeAm, zone)
        )

        // 05:00 已经过了翻天时刻，属于新的一天
        val fiveAm = millis(day.atTime(5, 0), zone)
        assertEquals(
            DayIndex.of(threeAm, zone) + 1,
            DayIndex.of(fiveAm, zone)
        )
    }

    @Test
    fun `翻天时刻恰好是 4 点整`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val day = LocalDate.of(2026, 9, 5)

        val justBefore = millis(day.atTime(3, 59), zone)
        val exactlyFour = millis(day.atTime(4, 0), zone)

        assertEquals(DayIndex.of(exactlyFour, zone) - 1, DayIndex.of(justBefore, zone))

        // 00:00 到 03:59 都归属前一个学习日，所以两者必须相等
        val midnight = millis(day.atStartOfDay(), zone)
        assertEquals(DayIndex.of(justBefore, zone), DayIndex.of(midnight, zone))
    }

    private fun millis(dateTime: LocalDateTime, zone: ZoneId): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()
}
