package com.yanhuozhang.offline.core

import com.yanhuozhang.offline.data.LedgerRecordEntity
import com.yanhuozhang.offline.data.LedgerStatus

data class MonthlyCategoryTotal(
    val categoryId: Long?,
    val expenseCents: Long,
)

object MonthlyExpenseCalculator {
    fun calculate(
        records: List<LedgerRecordEntity>,
        startTimeMillis: Long,
        endTimeMillis: Long,
    ): List<MonthlyCategoryTotal> {
        val totalsByCategory = mutableMapOf<Long?, Long>()
        for (record in records) {
            if (record.status != LedgerStatus.ACTIVE ||
                record.statisticsTimeMillis < startTimeMillis ||
                record.statisticsTimeMillis >= endTimeMillis
            ) {
                continue
            }
            val change = when (record.statisticsEffect) {
                StatisticsEffect.ADD_EXPENSE -> record.amountCents
                StatisticsEffect.SUBTRACT_EXPENSE -> -record.amountCents
                StatisticsEffect.ADD_INCOME,
                StatisticsEffect.NONE,
                -> 0L
            }
            if (change != 0L) {
                totalsByCategory[record.categoryId] =
                    totalsByCategory.getOrDefault(record.categoryId, 0L) + change
            }
        }
        return totalsByCategory
            .filterValues { amount -> amount != 0L }
            .map { (categoryId, amount) -> MonthlyCategoryTotal(categoryId, amount) }
            .sortedByDescending { item -> item.expenseCents }
    }
}
