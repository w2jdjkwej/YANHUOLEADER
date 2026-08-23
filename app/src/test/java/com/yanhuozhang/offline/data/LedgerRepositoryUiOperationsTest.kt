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
import kotlinx.coroutines.flow.first
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
class LedgerRepositoryUiOperationsTest {
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
    fun tearDown() = database.close()

    @Test
    fun `record can be edited deleted and restored`() = runBlocking {
        val id = repository.addManual(sampleInput(1000, "原备注"), 1_000)
        repository.updateRecord(id, sampleInput(2500, "新备注"), 2_000)
        repository.softDelete(id, 3_000)
        repository.restore(id, 4_000)

        val record = database.ledgerDao().findById(id)
        assertEquals(2500L, record?.amountCents)
        assertEquals("新备注", record?.note)
        assertEquals(LedgerStatus.ACTIVE, record?.status)
        assertEquals(1_000L, record?.createdAtMillis)
        assertEquals(4_000L, record?.updatedAtMillis)
    }

    @Test
    fun `pending event can merge into chosen record`() = runBlocking {
        val recordId = repository.addManual(sampleInput(2000, "已有账单"), 500)
        val pending = repository.ingest(unclearNotification("merge-key")) as IngestionOutcome.Pending

        repository.mergePending(pending.eventId, recordId)

        val event = database.eventDao().findById(pending.eventId)
        assertEquals(recordId, event?.recordId)
        assertEquals(EventStatus.LINKED, event?.status)
    }

    @Test
    fun `ignored pending event stays ignored on replay`() = runBlocking {
        val raw = unclearNotification("ignore-key")
        val pending = repository.ingest(raw) as IngestionOutcome.Pending

        repository.ignorePending(pending.eventId, 2_000)
        val replay = repository.ingest(raw)

        assertEquals(EventStatus.IGNORED, database.eventDao().findById(pending.eventId)?.status)
        assertEquals(IngestionOutcome.Ignored, replay)
    }

    @Test
    fun `category can be added renamed reordered and disabled`() = runBlocking {
        val id = repository.addCategory("咖啡", AccountingType.EXPENSE, 20)
        repository.updateCategory(id, "饮品", 2, false)

        val category = database.categoryDao().findById(id)
        assertEquals("饮品", category?.name)
        assertEquals(2, category?.sortOrder)
        assertEquals(false, category?.isActive)
    }

    @Test
    fun `personal rule is saved only by explicit action`() = runBlocking {
        repository.saveRule(
            PersonalRuleInput(
                matchText = "食堂",
                direction = TransactionDirection.OUT,
                paymentChannel = null,
                categoryId = null,
                accountingType = AccountingType.EXPENSE,
                statisticsEffect = StatisticsEffect.ADD_EXPENSE,
            ),
            nowMillis = 3_000,
        )

        val rules = repository.rules.first()
        assertEquals(1, rules.size)
        assertEquals("食堂", rules.first().matchText)
        assertTrue(rules.first().isActive)
    }

    @Test
    fun `explicit personal rule overrides default uncategorized auto record`() = runBlocking {
        val diningCategory = database.categoryDao().getAll().first { category -> category.name == "餐饮" }
        repository.saveRule(
            PersonalRuleInput(
                matchText = "已支付",
                direction = TransactionDirection.OUT,
                paymentChannel = PaymentChannel.WECHAT,
                categoryId = diningCategory.id,
                accountingType = AccountingType.EXPENSE,
                statisticsEffect = StatisticsEffect.ADD_EXPENSE,
            ),
            nowMillis = 500,
        )

        val outcome = repository.ingest(
            RawObservation(
                source = EventSource.WECHAT_NOTIFICATION,
                sourceEventId = "rule-key",
                title = "微信支付",
                content = "已支付￥18.80",
                observedAtMillis = 1_000,
            ),
        ) as IngestionOutcome.Created

        val record = database.ledgerDao().findById(outcome.recordId)
        assertEquals(diningCategory.id, record?.categoryId)
    }

    @Test
    fun `manual category learns counterparty and updates matching history`() = runBlocking {
        val categories = database.categoryDao().getAll()
        val uncategorized = categories.first { category -> category.name == "未分类" }
        val shopping = categories.first { category -> category.name == "购物" }
        val historicalId = repository.addManual(
            sampleInput(460, "").copy(
                categoryId = uncategorized.id,
                paymentChannel = PaymentChannel.WECHAT,
                counterparty = "静至安然（**兵）",
            ),
            nowMillis = 1_000,
        )
        repository.addManual(
            sampleInput(880, "").copy(
                categoryId = shopping.id,
                paymentChannel = PaymentChannel.WECHAT,
                counterparty = "静至安然 (**兵)",
            ),
            nowMillis = 2_000,
            personalRule = PersonalRuleInput(
                matchText = "静至安然 (**兵)",
                direction = TransactionDirection.OUT,
                paymentChannel = null,
                categoryId = shopping.id,
                accountingType = AccountingType.EXPENSE,
                statisticsEffect = StatisticsEffect.ADD_EXPENSE,
                updateHistoricalUncategorized = true,
            ),
        )

        assertEquals(shopping.id, database.ledgerDao().findById(historicalId)?.categoryId)
        assertEquals("静至安然", repository.rules.first().single().matchText)

        val automatic = repository.ingest(
            RawObservation(
                source = EventSource.WECHAT_PAYMENT_PAGE,
                sourceEventId = "learned-counterparty",
                title = "静至安然（**兵）",
                content = "支付成功\n静至安然（**兵）\n￥12.00",
                observedAtMillis = 3_000,
            ),
        ) as IngestionOutcome.Created
        assertEquals(shopping.id, database.ledgerDao().findById(automatic.recordId)?.categoryId)
    }

    @Test
    fun `newest category for the same counterparty updates existing rule`() = runBlocking {
        val categories = database.categoryDao().getAll()
        val shopping = categories.first { category -> category.name == "购物" }
        val entertainment = categories.first { category -> category.name == "娱乐" }
        val originalRule = PersonalRuleInput(
            matchText = "静至安然（**兵）",
            direction = TransactionDirection.OUT,
            paymentChannel = PaymentChannel.WECHAT,
            categoryId = shopping.id,
            accountingType = AccountingType.EXPENSE,
            statisticsEffect = StatisticsEffect.ADD_EXPENSE,
        )
        repository.saveRule(originalRule, 1_000)
        repository.saveRule(originalRule.copy(categoryId = entertainment.id), 2_000)

        val rules = repository.rules.first()
        assertEquals(1, rules.size)
        assertEquals(entertainment.id, rules.single().categoryId)
    }

    private fun sampleInput(amount: Long, note: String): LedgerInput {
        return LedgerInput(
            amountCents = amount,
            direction = TransactionDirection.OUT,
            accountingType = AccountingType.EXPENSE,
            statisticsEffect = StatisticsEffect.ADD_EXPENSE,
            categoryId = null,
            paymentChannel = PaymentChannel.CASH,
            counterparty = null,
            transactionTimeMillis = 800,
            relatedRecordId = null,
            note = note,
        )
    }

    private fun unclearNotification(id: String): RawObservation {
        return RawObservation(
            source = EventSource.WECHAT_NOTIFICATION,
            sourceEventId = id,
            title = "微信支付",
            content = "支付消息￥20.00",
            observedAtMillis = 1_000,
        )
    }
}
