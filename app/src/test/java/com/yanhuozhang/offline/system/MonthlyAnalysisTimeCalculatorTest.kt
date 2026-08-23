package com.yanhuozhang.offline.system

import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlyAnalysisTimeCalculatorTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `normal day schedules current month last day at 2330`() {
        val now = ZonedDateTime.of(2026, 7, 15, 8, 0, 0, 0, zone)

        val schedule = MonthlyAnalysisTimeCalculator.next(now)

        assertEquals(YearMonth.of(2026, 7), schedule.month)
        assertEquals(
            ZonedDateTime.of(2026, 7, 31, 23, 30, 0, 0, zone),
            schedule.runAt,
        )
    }

    @Test
    fun `late launch on last day schedules current month immediately`() {
        val now = ZonedDateTime.of(2026, 7, 31, 23, 45, 0, 0, zone)

        val schedule = MonthlyAnalysisTimeCalculator.next(now)

        assertEquals(YearMonth.of(2026, 7), schedule.month)
        assertTrue(schedule.runAt.isAfter(now))
        assertEquals(5, java.time.Duration.between(now, schedule.runAt).seconds)
    }

    @Test
    fun `day after completion schedules next month end`() {
        val now = ZonedDateTime.of(2026, 8, 1, 0, 5, 0, 0, zone)

        val schedule = MonthlyAnalysisTimeCalculator.next(now)

        assertEquals(YearMonth.of(2026, 8), schedule.month)
        assertEquals(31, schedule.runAt.dayOfMonth)
    }
}
