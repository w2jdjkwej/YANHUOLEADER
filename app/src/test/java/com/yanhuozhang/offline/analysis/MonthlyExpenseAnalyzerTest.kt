package com.yanhuozhang.offline.analysis

import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.StatisticsEffect
import com.yanhuozhang.offline.core.TransactionDirection
import com.yanhuozhang.offline.data.CategoryEntity
import com.yanhuozhang.offline.data.LedgerRecordEntity
import com.yanhuozhang.offline.data.LedgerStatus
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlyExpenseAnalyzerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val analyzer = MonthlyExpenseAnalyzer(zone)
    private val categories = listOf(
        category(1, "餐饮"),
        category(2, "购物"),
        category(3, "娱乐"),
    )

    @Test
    fun `summary separates expense and refund and handles zero previous month`() {
        val records = listOf(
            record(1, 5_000, 1, "餐厅", "2026-08-02T12:00:00+08:00"),
            record(2, 2_000, 2, "商店", "2026-08-03T20:00:00+08:00"),
            record(
                3,
                1_000,
                1,
                "餐厅",
                "2026-08-04T10:00:00+08:00",
                effect = StatisticsEffect.SUBTRACT_EXPENSE,
            ),
            record(4, 1_000, 1, "上月消费", "2026-07-01T10:00:00+08:00"),
            record(
                5,
                1_000,
                1,
                "上月退款",
                "2026-07-02T10:00:00+08:00",
                effect = StatisticsEffect.SUBTRACT_EXPENSE,
            ),
        )

        val result = requireNotNull(
            analyzer.analyze(records, categories, YearMonth.of(2026, 8), time("2026-08-24T08:00:00+08:00")),
        )

        assertEquals(7_000L, result.summary.grossExpenseCents)
        assertEquals(1_000L, result.summary.refundCents)
        assertEquals(6_000L, result.summary.netExpenseCents)
        assertEquals(2, result.summary.expenseOrderCount)
        assertEquals(3_500L, result.summary.averageExpenseCents)
        assertEquals(0L, result.summary.previousMonthNetExpenseCents)
        assertNull(result.summary.monthOverMonthChangePercent)
        assertEquals(24, result.summary.elapsedCalendarDayCount)
        assertEquals(2, result.categoryStatistics.size)
        assertEquals("餐饮", result.categoryStatistics.first().category)
        assertEquals(4_000L, result.categoryStatistics.first().netExpenseCents)
        assertEquals(2, result.orders.count { order -> order.effect == "expense" })
        assertEquals(1, result.orders.count { order -> order.effect == "refund" })
    }

    @Test
    fun `behavior detects high frequency small large duplicate and night candidates`() {
        val records = listOf(
            record(1, 1_000, 1, "奶茶店", "2026-08-01T09:00:00+08:00"),
            record(2, 1_000, 1, "奶茶店", "2026-08-02T09:00:00+08:00"),
            record(3, 900, 1, "奶茶店", "2026-08-03T09:00:00+08:00"),
            record(4, 1_100, 1, "奶茶店", "2026-08-04T09:00:00+08:00"),
            record(5, 2_000, 2, "便利店", "2026-08-10T22:30:00+08:00"),
            record(6, 2_000, 2, "便利店", "2026-08-10T22:35:00+08:00"),
            record(7, 100_000, 2, "电脑商城", "2026-08-15T15:00:00+08:00", note = "电脑"),
        )

        val result = requireNotNull(
            analyzer.analyze(records, categories, YearMonth.of(2026, 8), time("2026-08-24T08:00:00+08:00")),
        )

        assertEquals(1, result.behaviorStatistics.highFrequencySmallExpenses.size)
        assertEquals("奶茶店", result.behaviorStatistics.highFrequencySmallExpenses.first().merchant)
        assertEquals(1, result.possibleDuplicates.size)
        assertEquals("便利店", result.possibleDuplicates.first().merchant)
        assertEquals(1, result.largeExpenseCandidates.size)
        assertTrue(result.largeExpenseCandidates.first().possibleOneTime == true)
        assertTrue(result.behaviorStatistics.timeStatistics.available)
        assertEquals(4_000L, result.behaviorStatistics.timeStatistics.nightExpense?.totalExpenseCents)
        assertEquals(8_000L, result.behaviorStatistics.expenseExcludingLargeCandidatesCents)
        assertTrue(result.concentration.top1OrderSharePercent > 90.0)
    }

    @Test
    fun `history creates baseline recurring candidate anomaly and trend only with enough months`() {
        val records = mutableListOf<LedgerRecordEntity>()
        records += record(1, 10_000, 1, "日常餐厅", "2026-05-05T12:00:00+08:00")
        records += record(2, 11_000, 1, "日常餐厅", "2026-06-05T12:00:00+08:00")
        records += record(3, 12_000, 1, "日常餐厅", "2026-07-05T12:00:00+08:00")
        records += record(4, 50_000, 2, "购物平台", "2026-08-05T12:00:00+08:00")
        records += record(5, 1_990, 3, "视频会员", "2026-05-10T08:00:00+08:00")
        records += record(6, 2_000, 3, "视频会员", "2026-06-10T08:00:00+08:00")
        records += record(7, 2_010, 3, "视频会员", "2026-07-10T08:00:00+08:00")
        records += record(8, 2_000, 3, "视频会员", "2026-08-10T08:00:00+08:00")

        val result = requireNotNull(
            analyzer.analyze(records, categories, YearMonth.of(2026, 8), time("2026-08-24T08:00:00+08:00")),
        )

        assertEquals(3, result.historicalStatistics.availableMonthCount)
        assertNotNull(result.historicalStatistics.baseline)
        assertNotNull(result.historicalStatistics.trend)
        assertTrue(result.historicalStatistics.anomalies.any { item ->
            item.type == "category" && item.name == "购物"
        })
        assertEquals(1, result.possibleRecurringPayments.size)
        assertEquals("视频会员", result.possibleRecurringPayments.first().merchant)
        assertTrue(result.possibleRecurringPayments.first().occurrenceMonths.size >= 3)
    }

    @Test
    fun `midnight only data skips time analysis and empty month returns null`() {
        val records = listOf(
            record(1, 1_000, 1, "早餐店", "2026-08-01T00:00:00+08:00"),
        )

        val august = analyzer.analyze(
            records,
            categories,
            YearMonth.of(2026, 8),
            time("2026-08-24T08:00:00+08:00"),
        )
        val september = analyzer.analyze(
            records,
            categories,
            YearMonth.of(2026, 9),
            time("2026-09-24T08:00:00+08:00"),
        )

        assertFalse(requireNotNull(august).behaviorStatistics.timeStatistics.available)
        assertNull(september)
    }

    private fun category(id: Long, name: String): CategoryEntity {
        return CategoryEntity(
            id = id,
            name = name,
            accountingType = AccountingType.EXPENSE,
            sortOrder = id.toInt(),
            isActive = true,
            isSystem = true,
        )
    }

    private fun record(
        id: Long,
        amountCents: Long,
        categoryId: Long,
        merchant: String,
        dateTime: String,
        effect: StatisticsEffect = StatisticsEffect.ADD_EXPENSE,
        note: String = "",
    ): LedgerRecordEntity {
        val timestamp = time(dateTime)
        return LedgerRecordEntity(
            id = id,
            amountCents = amountCents,
            direction = if (effect == StatisticsEffect.ADD_EXPENSE) {
                TransactionDirection.OUT
            } else {
                TransactionDirection.IN
            },
            accountingType = if (effect == StatisticsEffect.ADD_EXPENSE) {
                AccountingType.EXPENSE
            } else {
                AccountingType.REFUND
            },
            statisticsEffect = effect,
            categoryId = categoryId,
            paymentChannel = PaymentChannel.WECHAT,
            counterparty = merchant,
            transactionTimeMillis = timestamp,
            statisticsTimeMillis = timestamp,
            relatedRecordId = null,
            note = note,
            status = LedgerStatus.ACTIVE,
            createdAtMillis = timestamp,
            updatedAtMillis = timestamp,
        )
    }

    private fun time(value: String): Long {
        return ZonedDateTime.parse(value).toInstant().toEpochMilli()
    }
}
