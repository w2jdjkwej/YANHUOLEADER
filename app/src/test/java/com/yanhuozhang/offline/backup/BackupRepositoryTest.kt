package com.yanhuozhang.offline.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.StatisticsEffect
import com.yanhuozhang.offline.core.TransactionDirection
import com.yanhuozhang.offline.data.AppDatabase
import com.yanhuozhang.offline.data.DatabaseSeeder
import com.yanhuozhang.offline.data.LedgerInput
import com.yanhuozhang.offline.data.LedgerRepository
import com.yanhuozhang.offline.data.MonthlyAnalysisReportEntity
import com.yanhuozhang.offline.data.PersonalRuleInput
import com.yanhuozhang.offline.settings.DeepSeekModel
import com.yanhuozhang.offline.settings.DeepSeekThinkingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var ledgerRepository: LedgerRepository
    private lateinit var backupRepository: BackupRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DatabaseSeeder.seedDefaultCategories(database.categoryDao())
        ledgerRepository = LedgerRepository(database, ZoneId.of("Asia/Shanghai"))
        backupRepository = BackupRepository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `exports and restores complete business data`() = runBlocking {
        ledgerRepository.addManual(
            LedgerInput(
                amountCents = 1880,
                direction = TransactionDirection.OUT,
                accountingType = AccountingType.EXPENSE,
                statisticsEffect = StatisticsEffect.ADD_EXPENSE,
                categoryId = null,
                paymentChannel = PaymentChannel.CASH,
                counterparty = "早餐店",
                transactionTimeMillis = 1_000,
                relatedRecordId = null,
                note = "早餐",
            ),
            nowMillis = 1_100,
        )
        ledgerRepository.saveRule(
            PersonalRuleInput(
                matchText = "早餐店",
                direction = TransactionDirection.OUT,
                paymentChannel = PaymentChannel.CASH,
                categoryId = null,
                accountingType = AccountingType.EXPENSE,
                statisticsEffect = StatisticsEffect.ADD_EXPENSE,
            ),
            nowMillis = 1_200,
        )
        database.monthlyAnalysisDao().upsert(
            MonthlyAnalysisReportEntity(
                monthKey = "2026-07",
                totalExpenseCents = 1880,
                summary = "本月支出以餐饮为主。",
                observation1 = "餐饮占比最高。",
                observation2 = null,
                observation3 = null,
                suggestion = "可继续观察餐饮支出变化。",
                lightComment = "餐饮依旧稳坐支出榜首席。",
                model = DeepSeekModel.PRO.apiValue,
                thinkingMode = DeepSeekThinkingMode.ENABLED.apiValue,
                inputFingerprint = "fingerprint",
                generatedAtMillis = 1_300,
            ),
        )

        val envelope = backupRepository.exportEnvelope(
            createdAtMillis = 2_000,
            settings = BackupSettings(
                reminderEnabled = false,
                reminderHour = 19,
                reminderMinute = 30,
                appLockEnabled = false,
                lockTimeoutSeconds = 60,
                deepSeekModel = DeepSeekModel.PRO.apiValue,
                deepSeekThinkingMode = DeepSeekThinkingMode.ENABLED.apiValue,
            ),
        )
        val encoded = BackupCodec.encode(envelope)
        val decoded = BackupCodec.decode(encoded)

        database.clearAllTables()
        backupRepository.restoreEnvelope(decoded)

        assertEquals(1, database.ledgerDao().getAll().size)
        assertEquals(1, database.eventDao().getAll().size)
        assertEquals(25, database.categoryDao().count())
        assertEquals(1, ledgerRepository.rules.first().size)
        assertEquals(19, decoded.settings.reminderHour)
        assertEquals(false, decoded.settings.appLockEnabled)
        assertEquals(DeepSeekModel.PRO.apiValue, decoded.settings.deepSeekModel)
        assertEquals(
            DeepSeekThinkingMode.ENABLED.apiValue,
            decoded.settings.deepSeekThinkingMode,
        )
        assertEquals("早餐店", decoded.transactionEvents.single().counterparty)
        assertEquals(
            "餐饮依旧稳坐支出榜首席。",
            database.monthlyAnalysisDao().findByMonth("2026-07")?.lightComment,
        )
    }

    @Test
    fun `new backups use schema version 5`() {
        assertEquals(5, BackupEnvelope.CURRENT_SCHEMA_VERSION)
    }

    @Test
    fun `structured monthly report fields round trip through backup codec`() {
        val report = MonthlyAnalysisReportEntity(
            monthKey = "2026-08",
            totalExpenseCents = 5_000,
            summary = "结构化月报",
            observation1 = null,
            observation2 = null,
            observation3 = null,
            suggestion = null,
            lightComment = null,
            model = DeepSeekModel.FLASH.apiValue,
            thinkingMode = DeepSeekThinkingMode.DISABLED.apiValue,
            inputFingerprint = "structured-fingerprint",
            generatedAtMillis = 2_000,
            localAnalysisJson = "{\"month\":\"2026-08\"}",
            aiAnalysisJson = "{\"headline\":\"结构化月报\"}",
            reportSchemaVersion = 2,
        )
        val envelope = BackupEnvelope(
            createdAtMillis = 2_000,
            ledgerRecords = emptyList(),
            transactionEvents = emptyList(),
            categories = emptyList(),
            personalRules = emptyList(),
            ignoredFingerprints = emptyList(),
            settings = BackupSettings(),
            monthlyAnalysisReports = listOf(report),
        )

        val decoded = BackupCodec.decode(BackupCodec.encode(envelope))
        val decodedReport = decoded.monthlyAnalysisReports.single()

        assertEquals(2, decodedReport.reportSchemaVersion)
        assertEquals(report.localAnalysisJson, decodedReport.localAnalysisJson)
        assertEquals(report.aiAnalysisJson, decodedReport.aiAnalysisJson)
    }

    @Test
    fun `version 1 backup without event counterparty still restores`() = runBlocking {
        ledgerRepository.addManual(
            LedgerInput(
                amountCents = 1_000,
                direction = TransactionDirection.OUT,
                accountingType = AccountingType.EXPENSE,
                statisticsEffect = StatisticsEffect.ADD_EXPENSE,
                categoryId = null,
                paymentChannel = PaymentChannel.WECHAT,
                counterparty = "吉林大学",
                transactionTimeMillis = 1_000,
                relatedRecordId = null,
                note = "",
            ),
            nowMillis = 1_100,
        )
        val currentEnvelope = backupRepository.exportEnvelope(2_000, BackupSettings())
        val currentJson = Json.parseToJsonElement(
            BackupCodec.encode(currentEnvelope).toString(Charsets.UTF_8),
        ).jsonObject
        val legacyEvents = currentJson.getValue("transactionEvents").jsonArray.map { eventElement ->
            JsonObject(eventElement.jsonObject - "counterparty")
        }
        val legacySettings = JsonObject(
            currentJson.getValue("settings").jsonObject -
                setOf("deepSeekModel", "deepSeekThinkingMode"),
        )
        val legacyJson = JsonObject(
            (currentJson - "monthlyAnalysisReports") + mapOf(
                "schemaVersion" to JsonPrimitive(1),
                "transactionEvents" to JsonArray(legacyEvents),
                "settings" to legacySettings,
            ),
        ).toString()

        val decoded = BackupCodec.decode(legacyJson.toByteArray(Charsets.UTF_8))
        database.clearAllTables()
        backupRepository.restoreEnvelope(decoded)

        assertEquals(1, decoded.schemaVersion)
        assertEquals(DeepSeekModel.FLASH.apiValue, decoded.settings.deepSeekModel)
        assertEquals(
            DeepSeekThinkingMode.DISABLED.apiValue,
            decoded.settings.deepSeekThinkingMode,
        )
        assertEquals(1, database.ledgerDao().getAll().size)
        assertEquals(null, database.eventDao().getAll().single().counterparty)
    }

    @Test
    fun `invalid settings do not replace existing data`() = runBlocking {
        ledgerRepository.addManual(
            LedgerInput(
                amountCents = 500,
                direction = TransactionDirection.OUT,
                accountingType = AccountingType.EXPENSE,
                statisticsEffect = StatisticsEffect.ADD_EXPENSE,
                categoryId = null,
                paymentChannel = PaymentChannel.CASH,
                counterparty = null,
                transactionTimeMillis = 1_000,
                relatedRecordId = null,
                note = "保留的账单",
            ),
            nowMillis = 1_100,
        )
        val invalidEnvelope = backupRepository.exportEnvelope(
            createdAtMillis = 2_000,
            settings = BackupSettings(reminderHour = 24),
        ).copy(
            ledgerRecords = emptyList(),
            transactionEvents = emptyList(),
        )

        try {
            backupRepository.restoreEnvelope(invalidEnvelope)
            fail("无效设置应阻止恢复")
        } catch (_: BackupException) {
            // 预期结果：校验失败时不进入替换数据的事务。
        }

        assertEquals(1, database.ledgerDao().getAll().size)
    }
}
