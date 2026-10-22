package com.yanhuozhang.offline.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.StatisticsEffect
import com.yanhuozhang.offline.core.TransactionDirection
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenshotCategorySuggestionTest {
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
    fun `personal rule wins over local keywords`() = runBlocking {
        val shopping = categoryId("购物", AccountingType.EXPENSE)
        repository.saveRule(
            PersonalRuleInput(
                matchText = "吉林大学食堂",
                direction = TransactionDirection.OUT,
                paymentChannel = null,
                categoryId = shopping,
                accountingType = AccountingType.EXPENSE,
                statisticsEffect = StatisticsEffect.ADD_EXPENSE,
            ),
            nowMillis = 1_000,
        )

        assertEquals(shopping, suggest(counterparty = "吉林大学食堂"))
    }

    @Test
    fun `local keyword is used for expenses without a rule`() = runBlocking {
        assertEquals(categoryId("餐饮", AccountingType.EXPENSE), suggest(counterparty = "吉林大学食堂"))
        assertEquals(
            categoryId("餐饮", AccountingType.EXPENSE),
            suggest(counterparty = null, note = "支付宝 · 瑞幸咖啡 1 杯"),
        )
    }

    @Test
    fun `no local match leaves the choice to the model`() = runBlocking {
        assertNull(suggest(counterparty = "某某科技"))
        // 本地关键词只覆盖支出。
        assertNull(suggest(counterparty = "吉林大学食堂", accountingType = AccountingType.INCOME))
    }

    @Test
    fun `rule for another accounting type is ignored`() = runBlocking {
        repository.saveRule(
            PersonalRuleInput(
                matchText = "张三",
                direction = null,
                paymentChannel = null,
                categoryId = categoryId("其他", AccountingType.EXPENSE),
                accountingType = AccountingType.EXPENSE,
                statisticsEffect = StatisticsEffect.ADD_EXPENSE,
            ),
            nowMillis = 1_000,
        )

        assertNull(
            suggest(
                counterparty = "张三",
                accountingType = AccountingType.TRANSFER,
            ),
        )
    }

    private suspend fun suggest(
        counterparty: String?,
        note: String = "",
        accountingType: AccountingType = AccountingType.EXPENSE,
    ): Long? {
        return repository.suggestCategoryId(
            counterparty = counterparty,
            note = note,
            accountingType = accountingType,
            direction = TransactionDirection.OUT,
            paymentChannel = PaymentChannel.OTHER,
        )
    }

    private suspend fun categoryId(name: String, type: AccountingType): Long {
        return requireNotNull(database.categoryDao().findActiveByName(name, type)).id
    }
}
