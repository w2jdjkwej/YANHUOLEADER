package com.yanhuozhang.offline.recognition

import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.StatisticsEffect
import com.yanhuozhang.offline.core.TransactionDirection
import com.yanhuozhang.offline.data.LedgerRecordEntity
import com.yanhuozhang.offline.data.LedgerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PossibleDuplicateFinderTest {
    private val base = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun `matches same amount and direction across all channels within the window`() {
        val records = listOf(
            record(id = 1, amount = 1665, time = base + 3 * minute, channel = PaymentChannel.WECHAT),
            record(id = 2, amount = 1665, time = base - 8 * minute, channel = PaymentChannel.BANK_CARD),
            record(id = 3, amount = 1665, time = base + 11 * minute),
            record(id = 4, amount = 1666, time = base),
            record(id = 5, amount = 1665, time = base, direction = TransactionDirection.IN),
            record(id = 6, amount = 1665, time = base, status = LedgerStatus.DELETED),
        )

        val result = find(records, windowMillis = PossibleDuplicateFinder.KNOWN_TIME_WINDOW_MILLIS)

        assertEquals(listOf(1L, 2L), result.map { record -> record.id })
    }

    @Test
    fun `unknown screenshot time widens the window to a day`() {
        val records = listOf(record(id = 1, amount = 1665, time = base - 20 * 60 * minute))

        assertTrue(find(records, PossibleDuplicateFinder.KNOWN_TIME_WINDOW_MILLIS).isEmpty())
        assertEquals(1, find(records, PossibleDuplicateFinder.UNKNOWN_TIME_WINDOW_MILLIS).size)
    }

    @Test
    fun `at most three closest records are returned`() {
        val records = (1..5).map { index -> record(id = index.toLong(), amount = 1665, time = base + index * minute) }

        assertEquals(listOf(1L, 2L, 3L), find(records, PossibleDuplicateFinder.KNOWN_TIME_WINDOW_MILLIS).map { it.id })
    }

    private fun find(records: List<LedgerRecordEntity>, windowMillis: Long): List<LedgerRecordEntity> {
        return PossibleDuplicateFinder.find(
            records = records,
            amountCents = 1665,
            direction = TransactionDirection.OUT,
            transactionTimeMillis = base,
            windowMillis = windowMillis,
        )
    }

    private fun record(
        id: Long,
        amount: Long,
        time: Long,
        direction: TransactionDirection = TransactionDirection.OUT,
        channel: PaymentChannel = PaymentChannel.OTHER,
        status: LedgerStatus = LedgerStatus.ACTIVE,
    ): LedgerRecordEntity {
        return LedgerRecordEntity(
            id = id,
            amountCents = amount,
            direction = direction,
            accountingType = AccountingType.EXPENSE,
            statisticsEffect = StatisticsEffect.ADD_EXPENSE,
            categoryId = null,
            paymentChannel = channel,
            counterparty = null,
            transactionTimeMillis = time,
            relatedRecordId = null,
            note = "",
            status = status,
            createdAtMillis = time,
            updatedAtMillis = time,
        )
    }
}
