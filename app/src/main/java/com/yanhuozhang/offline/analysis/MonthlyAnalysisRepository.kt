package com.yanhuozhang.offline.analysis

import com.yanhuozhang.offline.data.AppDatabase
import com.yanhuozhang.offline.data.CategoryEntity
import com.yanhuozhang.offline.data.LedgerRecordEntity
import com.yanhuozhang.offline.data.MonthlyAnalysisReportEntity
import com.yanhuozhang.offline.settings.SettingsRepository
import java.security.MessageDigest
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

sealed interface MonthlyAnalysisGenerationResult {
    data class Generated(val report: MonthlyAnalysisReportEntity) :
        MonthlyAnalysisGenerationResult

    data class AlreadyCurrent(val report: MonthlyAnalysisReportEntity) :
        MonthlyAnalysisGenerationResult

    data object DeepSeekUnavailable : MonthlyAnalysisGenerationResult
    data object NoExpense : MonthlyAnalysisGenerationResult
    data class Failed(val reason: String) : MonthlyAnalysisGenerationResult
}

class MonthlyAnalysisRepository(
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val client: MonthlyAnalysisClient,
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
) {
    private val analyzer = MonthlyExpenseAnalyzer(zoneId)

    val latestReport: Flow<MonthlyAnalysisReportEntity?> =
        database.monthlyAnalysisDao().observeLatest()

    val reports: Flow<List<MonthlyAnalysisReportEntity>> =
        database.monthlyAnalysisDao().observeAllDescending()

    suspend fun generate(
        month: YearMonth,
        force: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ): MonthlyAnalysisGenerationResult {
        val settings = settingsRepository.settings.first()
        if (!settings.deepSeekEnabled || settings.deepSeekApiKey.isBlank()) {
            return MonthlyAnalysisGenerationResult.DeepSeekUnavailable
        }
        val snapshot = buildSnapshot(month, nowMillis)
            ?: return MonthlyAnalysisGenerationResult.NoExpense
        val existing = database.monthlyAnalysisDao().findByMonth(snapshot.monthKey)
        if (!force && existing?.inputFingerprint == snapshot.fingerprint) {
            return MonthlyAnalysisGenerationResult.AlreadyCurrent(existing)
        }
        return when (
            val apiResult = client.analyze(
                snapshot = snapshot,
                apiKey = settings.deepSeekApiKey,
                model = settings.deepSeekModel,
                thinkingMode = settings.deepSeekThinkingMode,
            )
        ) {
            is MonthlyAnalysisApiResult.Failure -> {
                MonthlyAnalysisGenerationResult.Failed(apiResult.reason)
            }
            is MonthlyAnalysisApiResult.Success -> {
                val content = apiResult.content
                val observations = content.keyFindings.map { finding ->
                    listOf(finding.title, finding.description)
                        .filter { text -> text.isNotBlank() }
                        .joinToString("：")
                }
                val report = MonthlyAnalysisReportEntity(
                    monthKey = snapshot.monthKey,
                    totalExpenseCents = snapshot.totalExpenseCents,
                    summary = content.headline.ifBlank { content.overallAssessment },
                    observation1 = observations.getOrNull(0),
                    observation2 = observations.getOrNull(1),
                    observation3 = observations.getOrNull(2),
                    suggestion = content.nextMonthSuggestions.firstOrNull(),
                    lightComment = null,
                    model = settings.deepSeekModel.apiValue,
                    thinkingMode = settings.deepSeekThinkingMode.apiValue,
                    inputFingerprint = snapshot.fingerprint,
                    generatedAtMillis = nowMillis,
                    localAnalysisJson = MonthlyAnalysisJson.format.encodeToString(
                        MonthlyAnalysisPayload.serializer(),
                        snapshot.payload,
                    ),
                    aiAnalysisJson = MonthlyAnalysisJson.format.encodeToString(
                        MonthlyAiAnalysis.serializer(),
                        content,
                    ),
                    reportSchemaVersion = CURRENT_REPORT_SCHEMA_VERSION,
                )
                database.monthlyAnalysisDao().upsert(report)
                MonthlyAnalysisGenerationResult.Generated(report)
            }
        }
    }

    suspend fun buildSnapshot(
        month: YearMonth,
        nowMillis: Long = System.currentTimeMillis(),
    ): MonthlyExpenseSnapshot? {
        val startMonth = month.minusMonths(6)
        val start = startMonth.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val records = database.ledgerDao().getActiveBetweenForStatistics(start, end)
        val categories = database.categoryDao().getAll()
        return createSnapshot(records, categories, month, nowMillis)
    }

    fun createSnapshot(
        records: List<LedgerRecordEntity>,
        categories: List<CategoryEntity>,
        month: YearMonth,
        nowMillis: Long = System.currentTimeMillis(),
    ): MonthlyExpenseSnapshot? {
        val payload = analyzer.analyze(
            records = records,
            categories = categories,
            month = month,
            nowMillis = nowMillis,
        ) ?: return null
        val fingerprintPayload = payload.copy(generatedAsOf = "")
        val fingerprintSource = MonthlyAnalysisJson.format.encodeToString(
            MonthlyAnalysisPayload.serializer(),
            fingerprintPayload,
        )
        return MonthlyExpenseSnapshot(
            payload = payload,
            fingerprint = sha256(fingerprintSource),
        )
    }

    fun decodeLocalReport(report: MonthlyAnalysisReportEntity?): MonthlyAnalysisPayload? {
        val value = report?.localAnalysisJson ?: return null
        return runCatching {
            MonthlyAnalysisJson.format.decodeFromString(
                MonthlyAnalysisPayload.serializer(),
                value,
            )
        }.getOrNull()
    }

    fun decodeAiReport(report: MonthlyAnalysisReportEntity?): MonthlyAiAnalysis? {
        val value = report?.aiAnalysisJson ?: return null
        return runCatching {
            MonthlyAnalysisJson.format.decodeFromString(
                MonthlyAiAnalysis.serializer(),
                value,
            )
        }.getOrNull()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    companion object {
        const val CURRENT_REPORT_SCHEMA_VERSION = 2
    }
}
