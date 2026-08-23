package com.yanhuozhang.offline.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.EventSource
import com.yanhuozhang.offline.core.ObservationKind
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.StatisticsEffect
import com.yanhuozhang.offline.core.TransactionDirection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LedgerDatabaseTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `seeds default categories only once`() = runBlocking {
        DatabaseSeeder.seedDefaultCategories(database.categoryDao())
        DatabaseSeeder.seedDefaultCategories(database.categoryDao())

        assertEquals(25, database.categoryDao().count())
        assertEquals("未分类", database.categoryDao().findUncategorizedExpense()?.name)
    }

    @Test
    fun `database registers migration from version 1 to 2`() {
        assertEquals(1, AppDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, AppDatabase.MIGRATION_1_2.endVersion)
        assertEquals(2, AppDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, AppDatabase.MIGRATION_2_3.endVersion)
    }

    @Test
    fun `soft deleted record is excluded from match candidates`() = runBlocking {
        val recordId = database.ledgerDao().insert(
            sampleRecord(amountCents = 1480, transactionTimeMillis = 1_000_000),
        )

        val beforeDelete = database.ledgerDao().findMatchCandidates(
            amountCents = 1480,
            direction = TransactionDirection.OUT,
            paymentChannel = PaymentChannel.WECHAT,
            startTimeMillis = 900_000,
            endTimeMillis = 1_100_000,
        )
        database.ledgerDao().softDelete(recordId, updatedAtMillis = 2_000_000)
        val afterDelete = database.ledgerDao().findMatchCandidates(
            amountCents = 1480,
            direction = TransactionDirection.OUT,
            paymentChannel = PaymentChannel.WECHAT,
            startTimeMillis = 900_000,
            endTimeMillis = 1_100_000,
        )

        assertEquals(1, beforeDelete.size)
        assertTrue(afterDelete.isEmpty())
    }

    @Test
    fun `one record keeps multiple observation events`() = runBlocking {
        val recordId = database.ledgerDao().insert(sampleRecord(870, 1_000_000))
        database.eventDao().insert(sampleEvent("notification-1", recordId, EventSource.WECHAT_NOTIFICATION))
        database.eventDao().insert(sampleEvent("sms-1", recordId, EventSource.WECHAT_SMS))

        val events = database.eventDao().findByRecordId(recordId)

        assertEquals(2, events.size)
        assertEquals(setOf(EventSource.WECHAT_NOTIFICATION, EventSource.WECHAT_SMS), events.map { it.source }.toSet())
    }

    private fun sampleRecord(amountCents: Long, transactionTimeMillis: Long): LedgerRecordEntity {
        return LedgerRecordEntity(
            amountCents = amountCents,
            direction = TransactionDirection.OUT,
            accountingType = AccountingType.EXPENSE,
            statisticsEffect = StatisticsEffect.ADD_EXPENSE,
            categoryId = null,
            paymentChannel = PaymentChannel.WECHAT,
            counterparty = null,
            transactionTimeMillis = transactionTimeMillis,
            relatedRecordId = null,
            note = "",
            status = LedgerStatus.ACTIVE,
            createdAtMillis = transactionTimeMillis,
            updatedAtMillis = transactionTimeMillis,
        )
    }

    private fun sampleEvent(
        sourceEventId: String,
        recordId: Long,
        source: EventSource,
    ): TransactionEventEntity {
        return TransactionEventEntity(
            recordId = recordId,
            source = source,
            sourceEventId = sourceEventId,
            sourceFingerprint = "fingerprint-$sourceEventId",
            rawText = "raw",
            amountCents = 870,
            direction = TransactionDirection.OUT,
            kind = ObservationKind.PAYMENT,
            transactionTimeMillis = 1_000_000,
            observedAtMillis = 1_000_100,
            status = EventStatus.LINKED,
            createdAtMillis = 1_000_100,
        )
    }
}
