package com.yanhuozhang.offline.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.EventSource
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.RawObservation
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
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LedgerRepositoryOperationsTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: LedgerRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DatabaseSeeder.seedDefaultCategories(database.categoryDao())
        repository = LedgerRepository(database, ZoneId.of("Asia/Shanghai"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `manual record stores one manual source event`() = runBlocking {
        val recordId = repository.addManual(
            LedgerInput(
                amountCents = 1234,
                direction = TransactionDirection.OUT,
                accountingType = AccountingType.EXPENSE,
                statisticsEffect = StatisticsEffect.ADD_EXPENSE,
                categoryId = null,
                paymentChannel = PaymentChannel.CASH,
                counterparty = null,
                transactionTimeMillis = 1_000_000,
                relatedRecordId = null,
                note = "早餐",
            ),
            nowMillis = 1_000_100,
        )

        val record = database.ledgerDao().findById(recordId)
        val events = database.eventDao().findByRecordId(recordId)
        assertEquals(1234L, record?.amountCents)
        assertEquals(1, events.size)
        assertEquals(EventSource.MANUAL, events.first().source)
    }

    @Test
    fun `deleted automatic record cannot be recreated by replay`() = runBlocking {
        val raw = paymentNotification("key-delete", 1_000_000, "已支付￥14.80")
        val created = repository.ingest(raw) as IngestionOutcome.Created

        repository.softDelete(created.recordId, nowMillis = 2_000_000)
        val replay = repository.ingest(raw)

        assertEquals(IngestionOutcome.Ignored, replay)
        assertEquals(LedgerStatus.DELETED, database.ledgerDao().findById(created.recordId)?.status)
    }

    @Test
    fun `pending event can become confirmed record`() = runBlocking {
        val raw = paymentNotification("key-pending", 1_000_000, "支付消息￥20.00")
        val pending = repository.ingest(raw) as IngestionOutcome.Pending

        val recordId = repository.confirmPendingAsNewRecord(
            eventId = pending.eventId,
            input = LedgerInput(
                amountCents = 2000,
                direction = TransactionDirection.OUT,
                accountingType = AccountingType.EXPENSE,
                statisticsEffect = StatisticsEffect.ADD_EXPENSE,
                categoryId = null,
                paymentChannel = PaymentChannel.WECHAT,
                counterparty = null,
                transactionTimeMillis = 1_000_000,
                relatedRecordId = null,
                note = "确认消费",
            ),
            nowMillis = 2_000_000,
        )

        val event = database.eventDao().findById(pending.eventId)
        assertEquals(recordId, event?.recordId)
        assertEquals(EventStatus.LINKED, event?.status)
        assertTrue(database.ledgerDao().findById(recordId) != null)
    }

    private fun paymentNotification(id: String, time: Long, content: String): RawObservation {
        return RawObservation(
            source = EventSource.WECHAT_NOTIFICATION,
            sourceEventId = id,
            title = "微信支付",
            content = content,
            observedAtMillis = time,
        )
    }
}
