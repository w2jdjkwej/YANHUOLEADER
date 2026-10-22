package com.yanhuozhang.offline.analysis

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class MonthlyAnalysisPayload(
    val month: String,
    val generatedAsOf: String,
    val summary: MonthlySummarySnapshot,
    val categoryStatistics: List<CategoryStatisticSnapshot>,
    val merchantStatistics: MerchantStatisticsSnapshot,
    val behaviorStatistics: BehaviorStatisticsSnapshot,
    val historicalStatistics: HistoricalStatisticsSnapshot,
    val possibleDuplicates: List<DuplicateCandidateSnapshot>,
    val possibleRecurringPayments: List<RecurringPaymentCandidateSnapshot>,
    val largeExpenseCandidates: List<ExpenseOrderSnapshot>,
    val concentration: ConcentrationSnapshot,
    val orders: List<ExpenseOrderSnapshot>,
)

data class MonthlyExpenseSnapshot(
    val payload: MonthlyAnalysisPayload,
    val fingerprint: String,
) {
    val monthKey: String
        get() = payload.month

    val totalExpenseCents: Long
        get() = payload.summary.netExpenseCents
}

@Serializable
data class MonthlySummarySnapshot(
    val grossExpenseCents: Long,
    val refundCents: Long,
    val netExpenseCents: Long,
    val expenseOrderCount: Int,
    val averageExpenseCents: Long?,
    val largestExpense: ExpenseOrderSnapshot?,
    val spendingDayCount: Int,
    val averagePerSpendingDayCents: Long?,
    val elapsedCalendarDayCount: Int,
    val averagePerCalendarDayCents: Long?,
    val previousMonthNetExpenseCents: Long?,
    val monthOverMonthChangeCents: Long?,
    val monthOverMonthChangePercent: Double?,
)

@Serializable
data class CategoryStatisticSnapshot(
    val category: String,
    val grossExpenseCents: Long,
    val refundCents: Long,
    val netExpenseCents: Long,
    val shareOfGrossPercent: Double,
    val expenseOrderCount: Int,
    val averageExpenseCents: Long?,
    val largestExpenseCents: Long?,
)

@Serializable
data class MerchantStatisticSnapshot(
    val merchant: String,
    val totalExpenseCents: Long,
    val expenseOrderCount: Int,
    val averageExpenseCents: Long,
    val shareOfGrossPercent: Double,
)

@Serializable
data class MerchantStatisticsSnapshot(
    val topByAmount: List<MerchantStatisticSnapshot>,
    val topByFrequency: List<MerchantStatisticSnapshot>,
)

@Serializable
data class ExpenseOrderSnapshot(
    val transactionTime: String,
    val amountCents: Long,
    val effect: String,
    val merchant: String?,
    val category: String,
    val paymentChannel: String,
    val note: String?,
    val possibleOneTime: Boolean? = null,
)

@Serializable
data class HighFrequencySmallExpenseSnapshot(
    val merchant: String,
    val category: String,
    val expenseOrderCount: Int,
    val totalExpenseCents: Long,
    val averageExpenseCents: Long,
)

@Serializable
data class PeriodStatisticSnapshot(
    val label: String,
    val totalExpenseCents: Long,
    val expenseOrderCount: Int,
    val calendarDayCount: Int,
    val averagePerCalendarDayCents: Long?,
)

@Serializable
data class WorkdayWeekendSnapshot(
    val workday: PeriodStatisticSnapshot,
    val weekend: PeriodStatisticSnapshot,
)

@Serializable
data class TimeBucketSnapshot(
    val label: String,
    val totalExpenseCents: Long,
    val expenseOrderCount: Int,
)

@Serializable
data class NightExpenseSnapshot(
    val totalExpenseCents: Long,
    val expenseOrderCount: Int,
    val categories: List<CategoryAmountSnapshot>,
    val merchants: List<MerchantAmountSnapshot>,
)

@Serializable
data class TimeStatisticsSnapshot(
    val available: Boolean,
    val buckets: List<TimeBucketSnapshot>,
    val nightExpense: NightExpenseSnapshot?,
)

@Serializable
data class CategoryAmountSnapshot(
    val category: String,
    val totalExpenseCents: Long,
)

@Serializable
data class MerchantAmountSnapshot(
    val merchant: String,
    val totalExpenseCents: Long,
)

@Serializable
data class BehaviorStatisticsSnapshot(
    val medianExpenseCents: Long?,
    val p75ExpenseCents: Long?,
    val p90ExpenseCents: Long?,
    val highFrequencySmallExpenses: List<HighFrequencySmallExpenseSnapshot>,
    val workdayWeekend: WorkdayWeekendSnapshot,
    val timeStatistics: TimeStatisticsSnapshot,
    val monthPeriods: List<PeriodStatisticSnapshot>,
    val expenseExcludingLargeCandidatesCents: Long,
)

@Serializable
data class MonthlyHistoryPointSnapshot(
    val month: String,
    val netExpenseCents: Long,
    val averageExpenseCents: Long?,
)

@Serializable
data class HistoricalCategorySnapshot(
    val category: String,
    val currentExpenseCents: Long,
    val historicalAverageCents: Long,
    val historicalMedianCents: Long,
    val historicalMinCents: Long,
    val historicalMaxCents: Long,
    val historicalStandardDeviationCents: Long,
    val currentChangeFromAverageCents: Long,
)

@Serializable
data class HistoricalAnomalySnapshot(
    val type: String,
    val name: String,
    val currentAmountCents: Long,
    val historicalAverageCents: Long,
    val historicalStandardDeviationCents: Long,
    val increaseCents: Long,
)

@Serializable
data class BaselineSnapshot(
    val normalMonthlyExpenseCents: Long,
    val currentDifferenceCents: Long,
    val positiveCategoryContributors: List<CategoryAmountSnapshot>,
)

@Serializable
data class TrendSnapshot(
    val totalExpense: String,
    val averageExpense: String,
    val categoryTrends: List<CategoryTrendSnapshot>,
)

@Serializable
data class CategoryTrendSnapshot(
    val category: String,
    val trend: String,
)

@Serializable
data class HistoricalStatisticsSnapshot(
    val availableMonthCount: Int,
    val months: List<MonthlyHistoryPointSnapshot>,
    val categories: List<HistoricalCategorySnapshot>,
    val anomalies: List<HistoricalAnomalySnapshot>,
    val baseline: BaselineSnapshot?,
    val trend: TrendSnapshot?,
)

@Serializable
data class DuplicateCandidateSnapshot(
    val merchant: String,
    val amountCents: Long,
    val paymentChannel: String,
    val firstTransactionTime: String,
    val secondTransactionTime: String,
    val gapMinutes: Long,
)

@Serializable
data class RecurringPaymentCandidateSnapshot(
    val merchant: String,
    val typicalAmountCents: Long,
    val occurrenceMonths: List<String>,
    val occurrenceCount: Int,
    val latestPaymentDate: String,
)

@Serializable
data class ConcentrationSnapshot(
    val top1OrderSharePercent: Double,
    val top3OrderSharePercent: Double,
    val top5OrderSharePercent: Double,
    val top10OrderSharePercent: Double,
    val top5MerchantSharePercent: Double,
)

@Serializable
data class MonthlyAiAnalysis(
    val headline: String = "",
    val overallAssessment: String = "",
    val keyFindings: List<AiKeyFinding> = emptyList(),
    val categoryInsights: List<AiCategoryInsight> = emptyList(),
    val behaviorInsights: List<AiInsight> = emptyList(),
    val abnormalExpenses: List<AiInsight> = emptyList(),
    val recurringPayments: List<AiInsight> = emptyList(),
    val trendAnalysis: String? = null,
    val dailyExpenseAssessment: String? = null,
    val potentialSavings: AiPotentialSavings? = null,
    val nextMonthSuggestions: List<String> = emptyList(),
    val positiveObservations: List<String> = emptyList(),
    val dataLimitations: List<String> = emptyList(),
)

@Serializable
data class AiKeyFinding(
    val title: String = "",
    val description: String = "",
    val evidence: String = "",
    val importance: String = "medium",
)

@Serializable
data class AiCategoryInsight(
    val category: String = "",
    val insight: String = "",
)

@Serializable
data class AiInsight(
    val title: String = "",
    val description: String = "",
    val evidence: String = "",
)

@Serializable
data class AiPotentialSavings(
    val minCents: Long,
    val maxCents: Long,
    val explanation: String,
)

object MonthlyAnalysisJson {
    val format = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
}
