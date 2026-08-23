package com.yanhuozhang.offline.analysis

import com.yanhuozhang.offline.core.MonthlyExpenseCalculator
import com.yanhuozhang.offline.data.AppDatabase
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
    val latestReport: Flow<MonthlyAnalysisReportEntity?> =
        database.monthlyAnalysisDao().observeLatest()

    suspend fun generate(
        month: YearMonth,
        force: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ): MonthlyAnalysisGenerationResult {
        val settings = settingsRepository.settings.first()
        if (!settings.deepSeekEnabled || settings.deepSeekApiKey.isBlank()) {
            return MonthlyAnalysisGenerationResult.DeepSeekUnavailable
        }
        val snapshot = buildSnapshot(month) ?: return MonthlyAnalysisGenerationResult.NoExpense
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
                val observations = apiResult.content.observations
                val report = MonthlyAnalysisReportEntity(
                    monthKey = snapshot.monthKey,
                    totalExpenseCents = snapshot.totalExpenseCents,
                    summary = apiResult.content.summary,
                    observation1 = observations.getOrNull(0),
                    observation2 = observations.getOrNull(1),
                    observation3 = observations.getOrNull(2),
                    suggestion = apiResult.content.suggestion,
                    lightComment = apiResult.content.lightComment,
                    model = settings.deepSeekModel.apiValue,
                    thinkingMode = settings.deepSeekThinkingMode.apiValue,
                    inputFingerprint = snapshot.fingerprint,
                    generatedAtMillis = nowMillis,
                )
                database.monthlyAnalysisDao().upsert(report)
                MonthlyAnalysisGenerationResult.Generated(report)
            }
        }
    }

    internal suspend fun buildSnapshot(month: YearMonth): MonthlyExpenseSnapshot? {
        val start = month.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val records = database.ledgerDao().getActiveBetweenForStatistics(start, end)
        val totals = MonthlyExpenseCalculator.calculate(records, start, end)
        val totalExpenseCents = totals.sumOf { item -> item.expenseCents }
        if (totalExpenseCents <= 0L) {
            return null
        }
        val categoryNames = database.categoryDao().getAll().associate { category ->
            category.id to category.name
        }
        val categories = totals.map { item ->
            MonthlyCategorySnapshot(
                name = categoryNames[item.categoryId] ?: "未分类",
                expenseCents = item.expenseCents,
                sharePercent = item.expenseCents * 100.0 / totalExpenseCents,
            )
        }
        val fingerprintSource = buildString {
            append(month)
            append('|')
            append(totalExpenseCents)
            for (category in categories) {
                append('|')
                append(category.name)
                append(':')
                append(category.expenseCents)
            }
        }
        return MonthlyExpenseSnapshot(
            monthKey = month.toString(),
            totalExpenseCents = totalExpenseCents,
            categories = categories,
            fingerprint = sha256(fingerprintSource),
        )
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
