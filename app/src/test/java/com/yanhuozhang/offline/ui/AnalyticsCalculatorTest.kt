package com.yanhuozhang.offline.ui

import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.StatisticsEffect
import com.yanhuozhang.offline.core.TransactionDirection
import com.yanhuozhang.offline.data.LedgerRecordEntity
import com.yanhuozhang.offline.data.LedgerStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AnalyticsCalculatorTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val now = LocalDate.of(2026, 6, 22).atTime(12, 0).atZone(zoneId)

    @Test
    fun `dashboard uses current month and today accounting effects`() {
        val records = listOf(
            record(500_000, StatisticsEffect.ADD_INCOME, LocalDate.of(2026, 6, 1)),
            record(12_000, StatisticsEffect.ADD_EXPENSE, LocalDate.of(2026, 6, 22)),
            record(2_000, StatisticsEffect.SUBTRACT_EXPENSE, LocalDate.of(2026, 6, 22)),
            record(80_000, StatisticsEffect.NONE, LocalDate.of(2026, 6, 22)),
            record(3_000, StatisticsEffect.ADD_EXPENSE, LocalDate.of(2026, 5, 20)),
        )

        val summary = AnalyticsCalculator.dashboard(records, pendingCount = 3, now = now)

        assertEquals(500_000L, summary.monthIncomeCents)
        assertEquals(10_000L, summary.monthExpenseCents)
        assertEquals(490_000L, summary.monthBalanceCents)
        assertEquals(10_000L, summary.todayExpenseCents)
        assertEquals(3, summary.pendingCount)
    }

    @Test
    fun `six month trend is chronological and includes empty months`() {
        val records = listOf(
            record(1_000, StatisticsEffect.ADD_EXPENSE, LocalDate.of(2026, 1, 10)),
            record(2_000, StatisticsEffect.ADD_EXPENSE, LocalDate.of(2026, 6, 10)),
        )

        val trend = AnalyticsCalculator.sixMonthTrend(records, now)

        assertEquals(listOf("1月", "2月", "3月", "4月", "5月", "6月"), trend.map { it.label })
        assertEquals(listOf(1_000L, 0L, 0L, 0L, 0L, 2_000L), trend.map { it.expenseCents })
    }

    @Test
    fun `category ranking subtracts refunds and ignores transfers`() {
        val records = listOf(
            record(5_000, StatisticsEffect.ADD_EXPENSE, LocalDate.of(2026, 6, 1), categoryId = 1),
            record(1_000, StatisticsEffect.SUBTRACT_EXPENSE, LocalDate.of(2026, 6, 2), categoryId = 1),
            record(3_000, StatisticsEffect.ADD_EXPENSE, LocalDate.of(2026, 6, 3), categoryId = 2),
            record(9_000, StatisticsEffect.NONE, LocalDate.of(2026, 6, 4), categoryId = 2),
        )

        val ranking = AnalyticsCalculator.categoryRanking(records, now)

        assertEquals(listOf(1L, 2L), ranking.map { it.categoryId })
        assertEquals(listOf(4_000L, 3_000L), ranking.map { it.expenseCents })
    }

    @Test
    fun `refund may keep actual date while adjusting original statistics month`() {
        val juneRefundForMay = record(
            amountCents = 1_000,
            effect = StatisticsEffect.SUBTRACT_EXPENSE,
            date = LocalDate.of(2026, 6, 22),
            statisticsDate = LocalDate.of(2026, 5, 10),
        )

        val summary = AnalyticsCalculator.dashboard(listOf(juneRefundForMay), 0, now)
        val trend = AnalyticsCalculator.sixMonthTrend(listOf(juneRefundForMay), now)

        assertEquals(0L, summary.monthExpenseCents)
        assertEquals(-1_000L, trend[4].expenseCents)
    }

    private fun record(
        amountCents: Long,
        effect: StatisticsEffect,
        date: LocalDate,
        categoryId: Long? = null,
        statisticsDate: LocalDate = date,
    ): LedgerRecordEntity {
        val direction = if (effect == StatisticsEffect.ADD_INCOME) TransactionDirection.IN else TransactionDirection.OUT
        val type = if (effect == StatisticsEffect.ADD_INCOME) AccountingType.INCOME else AccountingType.EXPENSE
        val time = date.atTime(10, 0).atZone(zoneId).toInstant().toEpochMilli()
        val statisticsTime = statisticsDate.atTime(10, 0).atZone(zoneId).toInstant().toEpochMilli()
        return LedgerRecordEntity(
            id = time + amountCents,
            amountCents = amountCents,
            direction = direction,
            accountingType = type,
            statisticsEffect = effect,
            categoryId = categoryId,
            paymentChannel = PaymentChannel.WECHAT,
            counterparty = null,
            transactionTimeMillis = time,
            statisticsTimeMillis = statisticsTime,
            relatedRecordId = null,
            note = "",
            status = LedgerStatus.ACTIVE,
            createdAtMillis = time,
            updatedAtMillis = time,
        )
    }
}
