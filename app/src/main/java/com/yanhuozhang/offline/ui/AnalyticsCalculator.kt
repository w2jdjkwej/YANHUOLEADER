package com.yanhuozhang.offline.ui

import com.yanhuozhang.offline.core.MonthlyExpenseCalculator
import com.yanhuozhang.offline.core.StatisticsCalculator
import com.yanhuozhang.offline.core.StatisticsEntry
import com.yanhuozhang.offline.data.LedgerRecordEntity
import com.yanhuozhang.offline.data.LedgerStatus
import java.time.ZonedDateTime

data class DashboardSummary(
    val monthIncomeCents: Long,
    val monthExpenseCents: Long,
    val todayExpenseCents: Long,
    val pendingCount: Int,
) {
    val monthBalanceCents: Long
        get() = monthIncomeCents - monthExpenseCents
}

data class MonthlyTrendPoint(
    val label: String,
    val incomeCents: Long,
    val expenseCents: Long,
)

data class CategoryExpense(
    val categoryId: Long?,
    val expenseCents: Long,
)

object AnalyticsCalculator {
    fun dashboard(
        records: List<LedgerRecordEntity>,
        pendingCount: Int,
        now: ZonedDateTime,
    ): DashboardSummary {
        val monthRecords = records.between(
            now.withDayOfMonth(1).toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli(),
            now.plusMonths(1).withDayOfMonth(1).toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli(),
        )
        val todayRecords = records.between(
            now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli(),
            now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli(),
        )
        val monthTotals = StatisticsCalculator.calculate(monthRecords.toStatisticsEntries())
        val todayTotals = StatisticsCalculator.calculate(todayRecords.toStatisticsEntries())
        return DashboardSummary(
            monthIncomeCents = monthTotals.incomeCents,
            monthExpenseCents = monthTotals.expenseCents,
            todayExpenseCents = todayTotals.expenseCents,
            pendingCount = pendingCount,
        )
    }

    fun sixMonthTrend(
        records: List<LedgerRecordEntity>,
        now: ZonedDateTime,
    ): List<MonthlyTrendPoint> {
        val points = mutableListOf<MonthlyTrendPoint>()
        val firstMonth = now.minusMonths(5).withDayOfMonth(1)
        for (offset in 0L..5L) {
            val month = firstMonth.plusMonths(offset)
            val start = month.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
            val end = month.plusMonths(1).toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
            val totals = StatisticsCalculator.calculate(records.between(start, end).toStatisticsEntries())
            points += MonthlyTrendPoint(
                label = "${month.monthValue}月",
                incomeCents = totals.incomeCents,
                expenseCents = totals.expenseCents,
            )
        }
        return points
    }

    fun categoryRanking(
        records: List<LedgerRecordEntity>,
        now: ZonedDateTime,
    ): List<CategoryExpense> {
        val start = now.withDayOfMonth(1).toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
        val end = now.plusMonths(1).withDayOfMonth(1).toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
        return MonthlyExpenseCalculator.calculate(records, start, end).map { item ->
            CategoryExpense(item.categoryId, item.expenseCents)
        }
    }

    private fun List<LedgerRecordEntity>.between(start: Long, end: Long): List<LedgerRecordEntity> {
        return filter { record ->
            record.status == LedgerStatus.ACTIVE &&
            record.statisticsTimeMillis >= start &&
                record.statisticsTimeMillis < end
        }
    }

    private fun List<LedgerRecordEntity>.toStatisticsEntries(): List<StatisticsEntry> {
        return map { record -> StatisticsEntry(record.amountCents, record.statisticsEffect) }
    }
}
