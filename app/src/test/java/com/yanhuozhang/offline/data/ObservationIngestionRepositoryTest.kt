package com.yanhuozhang.offline.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yanhuozhang.offline.core.EventSource
import com.yanhuozhang.offline.core.ObservationKind
import com.yanhuozhang.offline.core.RawObservation
import com.yanhuozhang.offline.core.TransactionDirection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ObservationIngestionRepositoryTest {
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
    fun `replayed notification creates one ledger record`() = runBlocking {
        val raw = paymentNotification("key-1", 1_000_000, "已支付￥14.80")

        val first = repository.ingest(raw)
        val second = repository.ingest(raw)

        assertTrue(first is IngestionOutcome.Created)
        assertEquals(IngestionOutcome.Duplicate, second)
        assertEquals(1, database.ledgerDao().getAll().size)
        assertEquals(1, database.eventDao().getAll().size)
    }

    @Test
    fun `notification and result sms merge into one record with two events`() = runBlocking {
        val notification = paymentNotification("notification-1", 1_000_000, "已支付￥8.70")
        val sms = RawObservation(
            source = EventSource.WECHAT_SMS,
            sourceEventId = "sms-1",
            title = null,
            content = "【微信支付】你手机号绑定的微信于1970-01-01 08:16:42付款成功8.70元。由于付款时手机网络不畅，本次支付结果使用短信提醒。",
            observedAtMillis = 1_003_000,
        )

        val first = repository.ingest(notification)
        val second = repository.ingest(sms)

        assertTrue(first is IngestionOutcome.Created)
        assertTrue(second is IngestionOutcome.Attached)
        val records = database.ledgerDao().getAll()
        assertEquals(1, records.size)
        assertEquals(2, database.eventDao().findByRecordId(records.first().id).size)
    }

    @Test
    fun `two same source payments close together stay pending`() = runBlocking {
        repository.ingest(paymentNotification("key-1", 1_000_000, "已支付￥6.00"))

        val outcome = repository.ingest(paymentNotification("key-2", 1_060_000, "已支付￥6.00"))

        assertTrue(outcome is IngestionOutcome.Pending)
        assertEquals(1, database.ledgerDao().getAll().size)
        assertEquals(EventStatus.PENDING, database.eventDao().getAll().last().status)
    }

    @Test
    fun `payment page creates record with counterparty before notification`() = runBlocking {
        val pageOutcome = repository.ingest(paymentPage("吉林大学", "10.00", 1_000_000))
        val notificationOutcome = repository.ingest(
            paymentNotification("notification-10", 1_001_000, "已支付￥10.00"),
        )

        assertTrue(pageOutcome is IngestionOutcome.Created)
        assertTrue(notificationOutcome is IngestionOutcome.Attached)
        val records = database.ledgerDao().getAll()
        assertEquals(1, records.size)
        assertEquals("吉林大学", records.single().counterparty)
        assertEquals(2, database.eventDao().findByRecordId(records.single().id).size)
    }

    @Test
    fun `local restaurant keyword classifies without remote fallback`() = runBlocking {
        val outcome = repository.ingest(
            paymentPage("满座儿餐饮服务（北京）有限公司", "23.50", 1_000_000),
        ) as IngestionOutcome.Created

        val dining = database.categoryDao().getAll().first { category -> category.name == "餐饮" }
        assertEquals(dining.id, database.ledgerDao().findById(outcome.recordId)?.categoryId)
        assertEquals(false, outcome.needsRemoteClassification)
    }

    @Test
    fun `unknown counterparty requests remote classification`() = runBlocking {
        val outcome = repository.ingest(
            paymentPage("静至安然（**兵）", "4.60", 1_000_000),
        ) as IngestionOutcome.Created

        val uncategorized = database.categoryDao().getAll().first { category -> category.name == "未分类" }
        assertEquals(uncategorized.id, database.ledgerDao().findById(outcome.recordId)?.categoryId)
        assertTrue(outcome.needsRemoteClassification)
    }

    @Test
    fun `payment page enriches notification record instead of creating another`() = runBlocking {
        repository.ingest(paymentNotification("notification-11", 1_000_000, "已支付￥10.00"))

        val pageOutcome = repository.ingest(paymentPage("吉林大学", "10.00", 1_001_000))

        assertTrue(pageOutcome is IngestionOutcome.Attached)
        val records = database.ledgerDao().getAll()
        assertEquals(1, records.size)
        assertEquals("吉林大学", records.single().counterparty)
        assertEquals("吉林大学", database.eventDao().getAll().last().counterparty)
    }

    @Test
    fun `page attach after unclassified notification classifies the record locally`() = runBlocking {
        val notificationOutcome = repository.ingest(
            paymentNotification("notification-12", 1_000_000, "已支付￥23.50"),
        ) as IngestionOutcome.Created
        assertTrue(notificationOutcome.needsRemoteClassification)
        assertTrue(repository.isUnclassifiedRecord(notificationOutcome.recordId))

        val pageOutcome = repository.ingest(
            paymentPage("满座儿餐饮服务（北京）有限公司", "23.50", 1_003_000),
        )

        val attached = pageOutcome as IngestionOutcome.Attached
        assertTrue(!attached.needsRemoteClassification)
        assertTrue(!repository.isUnclassifiedRecord(notificationOutcome.recordId))
        val dining = database.categoryDao().getAll().first { category -> category.name == "餐饮" }
        assertEquals(
            dining.id,
            database.ledgerDao().findById(notificationOutcome.recordId)?.categoryId,
        )
    }

    @Test
    fun `merchant appearing after blank payment page enriches and classifies the same record`() = runBlocking {
        val notificationOutcome = repository.ingest(
            paymentNotification("notification-13", 1_000_000, "已支付￥52.90"),
        ) as IngestionOutcome.Created
        val blankPageOutcome = repository.ingest(
            paymentPage(null, "52.90", 1_001_000, sourceEventId = "page-session-13"),
        )

        val merchantPageOutcome = repository.ingest(
            paymentPage("京东", "52.90", 1_001_500, sourceEventId = "page-session-13"),
        )

        assertTrue(blankPageOutcome is IngestionOutcome.Attached)
        assertTrue(merchantPageOutcome is IngestionOutcome.Attached)
        val record = database.ledgerDao().findById(notificationOutcome.recordId)
        val shopping = database.categoryDao().getAll().first { category -> category.name == "购物" }
        assertEquals("京东", record?.counterparty)
        assertEquals(shopping.id, record?.categoryId)
        assertEquals(1, database.ledgerDao().getAll().size)
        val events = database.eventDao().findByRecordId(notificationOutcome.recordId)
        assertEquals(3, events.size)
        assertTrue(events.all { event -> event.status == EventStatus.LINKED })
    }

    @Test
    fun `payment page without counterparty stays pending`() = runBlocking {
        val outcome = repository.ingest(paymentPage(null, "10.00", 1_000_000))

        assertTrue(outcome is IngestionOutcome.Pending)
        assertTrue(database.ledgerDao().getAll().isEmpty())
        assertEquals(EventStatus.PENDING, database.eventDao().getAll().single().status)
    }

    @Test
    fun `sent red packet creates pending event without ordinary expense record`() = runBlocking {
        val raw = RawObservation(
            source = EventSource.WECHAT_PAYMENT_PAGE,
            sourceEventId = "red-packet-1000000-1",
            title = null,
            content = "微信红包已发送\n￥0.01",
            observedAtMillis = 1_000_000,
        )

        val outcome = repository.ingest(raw)

        assertEquals(IngestionOutcome.Pending(1, "红包需要确认"), outcome)
        assertTrue(database.ledgerDao().getAll().isEmpty())
        val event = database.eventDao().getAll().single()
        assertEquals(1L, event.amountCents)
        assertEquals(TransactionDirection.OUT, event.direction)
        assertEquals(ObservationKind.RED_PACKET, event.kind)
        assertEquals(EventStatus.PENDING, event.status)
        assertNull(event.recordId)
        assertNull(event.counterparty)
    }

    private fun paymentNotification(
        sourceEventId: String,
        observedAtMillis: Long,
        content: String,
    ): RawObservation {
        return RawObservation(
            source = EventSource.WECHAT_NOTIFICATION,
            sourceEventId = sourceEventId,
            title = "微信支付",
            content = content,
            observedAtMillis = observedAtMillis,
        )
    }

    private fun paymentPage(
        counterparty: String?,
        amount: String,
        observedAtMillis: Long,
        sourceEventId: String = "page-$observedAtMillis-$amount-${counterparty.orEmpty()}",
    ): RawObservation {
        return RawObservation(
            source = EventSource.WECHAT_PAYMENT_PAGE,
            sourceEventId = sourceEventId,
            title = counterparty,
            content = "支付成功\n${counterparty.orEmpty()}\n￥$amount",
            observedAtMillis = observedAtMillis,
        )
    }
}
