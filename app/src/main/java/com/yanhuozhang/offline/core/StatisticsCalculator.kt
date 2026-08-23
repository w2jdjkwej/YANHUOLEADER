package com.yanhuozhang.offline.core

data class StatisticsEntry(
    val amountCents: Long,
    val effect: StatisticsEffect,
)

data class StatisticsTotals(
    val incomeCents: Long,
    val expenseCents: Long,
) {
    val balanceCents: Long
        get() = incomeCents - expenseCents
}

object StatisticsCalculator {
    fun calculate(entries: List<StatisticsEntry>): StatisticsTotals {
        var incomeCents = 0L
        var expenseCents = 0L

        for (entry in entries) {
            when (entry.effect) {
                StatisticsEffect.ADD_INCOME -> incomeCents += entry.amountCents
                StatisticsEffect.ADD_EXPENSE -> expenseCents += entry.amountCents
                StatisticsEffect.SUBTRACT_EXPENSE -> expenseCents -= entry.amountCents
                StatisticsEffect.NONE -> Unit
            }
        }

        return StatisticsTotals(
            incomeCents = incomeCents,
            expenseCents = expenseCents,
        )
    }
}
