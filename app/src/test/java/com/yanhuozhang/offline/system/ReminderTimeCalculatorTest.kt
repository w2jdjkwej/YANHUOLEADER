package com.yanhuozhang.offline.system

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.ZonedDateTime

class ReminderTimeCalculatorTest {
    @Test
    fun `uses same day when reminder time is still ahead`() {
        val now = ZonedDateTime.parse("2026-06-22T19:30:00+08:00[Asia/Shanghai]")

        val delay = ReminderTimeCalculator.delayUntilNext(now, 20, 0)

        assertEquals(Duration.ofMinutes(30), delay)
    }

    @Test
    fun `uses next day when reminder time has passed`() {
        val now = ZonedDateTime.parse("2026-06-22T20:30:00+08:00[Asia/Shanghai]")

        val delay = ReminderTimeCalculator.delayUntilNext(now, 20, 0)

        assertEquals(Duration.ofHours(23).plusMinutes(30), delay)
    }
}
