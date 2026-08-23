package com.yanhuozhang.offline.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsCalculatorTest {
    @Test
    fun `calculates income expense refund and ignores transfers`() {
        val entries = listOf(
            StatisticsEntry(500_000, StatisticsEffect.ADD_INCOME),
            StatisticsEntry(12_000, StatisticsEffect.ADD_EXPENSE),
            StatisticsEntry(2_000, StatisticsEffect.SUBTRACT_EXPENSE),
            StatisticsEntry(80_000, StatisticsEffect.NONE),
        )

        val totals = StatisticsCalculator.calculate(entries)

        assertEquals(500_000L, totals.incomeCents)
        assertEquals(10_000L, totals.expenseCents)
        assertEquals(490_000L, totals.balanceCents)
    }

    @Test
    fun `expense cannot become negative after refund`() {
        val totals = StatisticsCalculator.calculate(
            listOf(StatisticsEntry(1_000, StatisticsEffect.SUBTRACT_EXPENSE)),
        )

        assertEquals(-1_000L, totals.expenseCents)
        assertEquals(1_000L, totals.balanceCents)
    }
}
