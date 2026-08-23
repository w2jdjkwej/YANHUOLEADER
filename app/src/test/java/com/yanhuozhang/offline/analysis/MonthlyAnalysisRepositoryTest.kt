package com.yanhuozhang.offline.analysis

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.StatisticsEffect
import com.yanhuozhang.offline.core.TransactionDirection
import com.yanhuozhang.offline.data.AppDatabase
import com.yanhuozhang.offline.data.DatabaseSeeder
import com.yanhuozhang.offline.data.LedgerRecordEntity
import com.yanhuozhang.offline.data.LedgerStatus
import com.yanhuozhang.offline.settings.DeepSeekModel
import com.yanhuozhang.offline.settings.DeepSeekThinkingMode
import com.yanhuozhang.offline.settings.SettingsRepository
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
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
class MonthlyAnalysisRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var settingsRepository: SettingsRepository
    private var apiCalls = 0

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DatabaseSeeder.seedDefaultCategories(database.categoryDao())
        settingsRepository = SettingsRepository(context)
        settingsRepository.setDeepSeek(
            enabled = true,
            apiKey = "test-key",
            model = DeepSeekModel.PRO,
            thinkingMode = DeepSeekThinkingMode.ENABLED,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `generation is saved and not repeated`() = runBlocking {
        val food = requireNotNull(
            database.categoryDao().findActiveByName("餐饮", AccountingType.EXPENSE),
        )
        val transactionTime = ZonedDateTime.of(
            2026,
            7,
            15,
            12,
            0,
            0,
            0,
            ZoneId.of("Asia/Shanghai"),
        ).toInstant().toEpochMilli()
        database.ledgerDao().insert(
            LedgerRecordEntity(
                amountCents = 2350,
                direction = TransactionDirection.OUT,
                accountingType = AccountingType.EXPENSE,
                statisticsEffect = StatisticsEffect.ADD_EXPENSE,
                categoryId = food.id,
                paymentChannel = PaymentChannel.WECHAT,
                counterparty = "测试餐厅",
                transactionTimeMillis = transactionTime,
                relatedRecordId = null,
                note = "",
                status = LedgerStatus.ACTIVE,
                createdAtMillis = transactionTime,
                updatedAtMillis = transactionTime,
            ),
        )
        val repository = MonthlyAnalysisRepository(
            database = database,
            settingsRepository = settingsRepository,
            client = MonthlyAnalysisClient { snapshot, _, model, thinkingMode ->
                apiCalls += 1
                assertEquals("2026-07", snapshot.monthKey)
                assertEquals(2350, snapshot.totalExpenseCents)
                assertEquals(DeepSeekModel.PRO, model)
                assertEquals(DeepSeekThinkingMode.ENABLED, thinkingMode)
                MonthlyAnalysisApiResult.Success(
                    MonthlyAnalysisContent(
                        summary = "本月餐饮支出为主要支出。",
                        observations = listOf("餐饮占比为100%。"),
                        suggestion = null,
                        lightComment = "餐饮依旧稳坐支出榜首席。",
                    ),
                )
            },
        )

        val first = repository.generate(YearMonth.of(2026, 7), nowMillis = 1_000)
        val second = repository.generate(YearMonth.of(2026, 7), nowMillis = 2_000)

        assertTrue(first is MonthlyAnalysisGenerationResult.Generated)
        assertTrue(second is MonthlyAnalysisGenerationResult.AlreadyCurrent)
        assertEquals(1, apiCalls)
        assertEquals(
            "餐饮依旧稳坐支出榜首席。",
            database.monthlyAnalysisDao().findByMonth("2026-07")?.lightComment,
        )
    }
}
