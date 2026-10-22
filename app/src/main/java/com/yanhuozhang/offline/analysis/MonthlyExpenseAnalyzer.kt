package com.yanhuozhang.offline.analysis

import com.yanhuozhang.offline.core.CounterpartyNormalizer
import com.yanhuozhang.offline.core.StatisticsEffect
import com.yanhuozhang.offline.data.CategoryEntity
import com.yanhuozhang.offline.data.LedgerRecordEntity
import com.yanhuozhang.offline.data.LedgerStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

class MonthlyExpenseAnalyzer(
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
) {
    fun analyze(
        records: List<LedgerRecordEntity>,
        categories: List<CategoryEntity>,
        month: YearMonth,
        nowMillis: Long,
    ): MonthlyAnalysisPayload? {
        val categoryNames = categories.associate { category -> category.id to category.name }
        val relevantRecords = records.filter { record ->
            record.status == LedgerStatus.ACTIVE &&
                (record.statisticsEffect == StatisticsEffect.ADD_EXPENSE ||
                    record.statisticsEffect == StatisticsEffect.SUBTRACT_EXPENSE)
        }
        val currentRecords = relevantRecords.filter { record -> record.statisticsMonth() == month }
        if (currentRecords.isEmpty()) {
            return null
        }

        val currentExpenses = currentRecords
            .filter { record -> record.statisticsEffect == StatisticsEffect.ADD_EXPENSE }
            .map { record -> record.toAnalyzedRecord(categoryNames) }
        val currentRefunds = currentRecords
            .filter { record -> record.statisticsEffect == StatisticsEffect.SUBTRACT_EXPENSE }
            .map { record -> record.toAnalyzedRecord(categoryNames) }

        val grossExpenseCents = currentExpenses.sumOf { item -> item.record.amountCents }
        val refundCents = currentRefunds.sumOf { item -> item.record.amountCents }
        val netExpenseCents = grossExpenseCents - refundCents
        val amounts = currentExpenses.map { item -> item.record.amountCents }.sorted()
        val medianExpenseCents = percentile(amounts, 0.50)
        val p75ExpenseCents = percentile(amounts, 0.75)
        val p90ExpenseCents = percentile(amounts, 0.90)
        val elapsedEndDate = elapsedEndDate(month, nowMillis)

        val historicalMonths = (6L downTo 1L).map { offset -> month.minusMonths(offset) }
        val recordsByMonth = relevantRecords.groupBy { record -> record.statisticsMonth() }
        val observedHistoryMonths = historicalMonths.filter { historicalMonth ->
            recordsByMonth[historicalMonth].orEmpty().isNotEmpty()
        }
        val previousMonthRecords = recordsByMonth[month.minusMonths(1)].orEmpty()
        val hasPreviousMonthData = previousMonthRecords.isNotEmpty()
        val previousMonthNetExpenseCents = if (hasPreviousMonthData) {
            netExpense(previousMonthRecords)
        } else {
            null
        }

        val largeExpenses = findLargeExpenses(
            currentExpenses = currentExpenses,
            allRelevantRecords = relevantRecords,
            month = month,
            medianExpenseCents = medianExpenseCents,
            p90ExpenseCents = p90ExpenseCents,
        )
        val oneTimeLargeTotal = largeExpenses
            .filter { order -> order.possibleOneTime == true }
            .sumOf { order -> order.amountCents }

        val payload = MonthlyAnalysisPayload(
            month = month.toString(),
            generatedAsOf = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(nowMillis),
                zoneId,
            ).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            summary = buildSummary(
                currentExpenses = currentExpenses,
                grossExpenseCents = grossExpenseCents,
                refundCents = refundCents,
                netExpenseCents = netExpenseCents,
                elapsedEndDate = elapsedEndDate,
                previousMonthNetExpenseCents = previousMonthNetExpenseCents,
            ),
            categoryStatistics = buildCategoryStatistics(
                currentExpenses,
                currentRefunds,
                grossExpenseCents,
            ),
            merchantStatistics = buildMerchantStatistics(currentExpenses, grossExpenseCents),
            behaviorStatistics = BehaviorStatisticsSnapshot(
                medianExpenseCents = medianExpenseCents,
                p75ExpenseCents = p75ExpenseCents,
                p90ExpenseCents = p90ExpenseCents,
                highFrequencySmallExpenses = findHighFrequencySmallExpenses(
                    currentExpenses,
                    medianExpenseCents,
                ),
                workdayWeekend = buildWorkdayWeekend(currentExpenses, month, elapsedEndDate),
                timeStatistics = buildTimeStatistics(currentExpenses),
                monthPeriods = buildMonthPeriods(currentExpenses, month, elapsedEndDate),
                expenseExcludingLargeCandidatesCents =
                    (grossExpenseCents - oneTimeLargeTotal).coerceAtLeast(0L),
            ),
            historicalStatistics = buildHistoricalStatistics(
                relevantRecords = relevantRecords,
                currentExpenses = currentExpenses,
                month = month,
                observedHistoryMonths = observedHistoryMonths,
                recordsByMonth = recordsByMonth,
                medianExpenseCents = medianExpenseCents,
                categoryNames = categoryNames,
            ),
            possibleDuplicates = findPossibleDuplicates(currentExpenses),
            possibleRecurringPayments = findPossibleRecurringPayments(
                relevantRecords = relevantRecords,
                categoryNames = categoryNames,
                month = month,
            ),
            largeExpenseCandidates = largeExpenses,
            concentration = buildConcentration(currentExpenses, grossExpenseCents),
            orders = currentRecords
                .sortedWith(compareBy<LedgerRecordEntity> { it.transactionTimeMillis }.thenBy { it.id })
                .map { record -> record.toAnalyzedRecord(categoryNames).toSnapshot() },
        )
        return payload
    }

    private fun buildSummary(
        currentExpenses: List<AnalyzedRecord>,
        grossExpenseCents: Long,
        refundCents: Long,
        netExpenseCents: Long,
        elapsedEndDate: LocalDate,
        previousMonthNetExpenseCents: Long?,
    ): MonthlySummarySnapshot {
        val spendingDayCount = currentExpenses
            .map { item -> item.dateTime.toLocalDate() }
            .distinct()
            .size
        val elapsedCalendarDayCount = elapsedEndDate.dayOfMonth
        val largest = currentExpenses.maxWithOrNull(
            compareBy<AnalyzedRecord> { it.record.amountCents }.thenBy { it.record.transactionTimeMillis },
        )
        val change = previousMonthNetExpenseCents?.let { previous -> netExpenseCents - previous }
        val changePercent = if (previousMonthNetExpenseCents != null && previousMonthNetExpenseCents != 0L) {
            change!! * 100.0 / previousMonthNetExpenseCents
        } else {
            null
        }
        return MonthlySummarySnapshot(
            grossExpenseCents = grossExpenseCents,
            refundCents = refundCents,
            netExpenseCents = netExpenseCents,
            expenseOrderCount = currentExpenses.size,
            averageExpenseCents = average(currentExpenses.map { item -> item.record.amountCents }),
            largestExpense = largest?.toSnapshot(),
            spendingDayCount = spendingDayCount,
            averagePerSpendingDayCents = divide(netExpenseCents, spendingDayCount),
            elapsedCalendarDayCount = elapsedCalendarDayCount,
            averagePerCalendarDayCents = divide(netExpenseCents, elapsedCalendarDayCount),
            previousMonthNetExpenseCents = previousMonthNetExpenseCents,
            monthOverMonthChangeCents = change,
            monthOverMonthChangePercent = changePercent?.roundTo(2),
        )
    }

    private fun buildCategoryStatistics(
        expenses: List<AnalyzedRecord>,
        refunds: List<AnalyzedRecord>,
        grossExpenseCents: Long,
    ): List<CategoryStatisticSnapshot> {
        val expenseGroups = expenses.groupBy { item -> item.category }
        val refundGroups = refunds.groupBy { item -> item.category }
        val names = (expenseGroups.keys + refundGroups.keys).distinct()
        val result = mutableListOf<CategoryStatisticSnapshot>()
        for (name in names) {
            val categoryExpenses = expenseGroups[name].orEmpty()
            val categoryRefunds = refundGroups[name].orEmpty()
            val gross = categoryExpenses.sumOf { item -> item.record.amountCents }
            val refund = categoryRefunds.sumOf { item -> item.record.amountCents }
            result += CategoryStatisticSnapshot(
                category = name,
                grossExpenseCents = gross,
                refundCents = refund,
                netExpenseCents = gross - refund,
                shareOfGrossPercent = percent(gross, grossExpenseCents),
                expenseOrderCount = categoryExpenses.size,
                averageExpenseCents = average(categoryExpenses.map { item -> item.record.amountCents }),
                largestExpenseCents = categoryExpenses.maxOfOrNull { item -> item.record.amountCents },
            )
        }
        return result.sortedWith(
            compareByDescending<CategoryStatisticSnapshot> { it.netExpenseCents }
                .thenByDescending { it.grossExpenseCents }
                .thenBy { it.category },
        )
    }

    private fun buildMerchantStatistics(
        expenses: List<AnalyzedRecord>,
        grossExpenseCents: Long,
    ): MerchantStatisticsSnapshot {
        val statistics = expenses
            .groupBy { item -> item.merchantKey }
            .map { (_, items) ->
                val total = items.sumOf { item -> item.record.amountCents }
                MerchantStatisticSnapshot(
                    merchant = items.first().merchant,
                    totalExpenseCents = total,
                    expenseOrderCount = items.size,
                    averageExpenseCents = divide(total, items.size) ?: 0L,
                    shareOfGrossPercent = percent(total, grossExpenseCents),
                )
            }
        return MerchantStatisticsSnapshot(
            topByAmount = statistics.sortedWith(
                compareByDescending<MerchantStatisticSnapshot> { it.totalExpenseCents }
                    .thenByDescending { it.expenseOrderCount }
                    .thenBy { it.merchant },
            ).take(10),
            topByFrequency = statistics.sortedWith(
                compareByDescending<MerchantStatisticSnapshot> { it.expenseOrderCount }
                    .thenByDescending { it.totalExpenseCents }
                    .thenBy { it.merchant },
            ).take(10),
        )
    }

    private fun findHighFrequencySmallExpenses(
        expenses: List<AnalyzedRecord>,
        medianExpenseCents: Long?,
    ): List<HighFrequencySmallExpenseSnapshot> {
        if (medianExpenseCents == null) {
            return emptyList()
        }
        val groups = expenses
            .filter { item -> item.merchantKey.isNotBlank() }
            .groupBy { item -> item.merchantKey to item.category }
        val groupCounts = groups.values.map { items -> items.size.toLong() }.sorted()
        val frequencyP75 = percentile(groupCounts, 0.75)?.toInt() ?: return emptyList()
        val minimumFrequency = max(3, frequencyP75)
        val candidates = mutableListOf<HighFrequencySmallExpenseSnapshot>()
        for ((_, items) in groups) {
            val total = items.sumOf { item -> item.record.amountCents }
            val itemAverage = divide(total, items.size) ?: continue
            if (items.size >= minimumFrequency && itemAverage <= medianExpenseCents) {
                candidates += HighFrequencySmallExpenseSnapshot(
                    merchant = items.first().merchant,
                    category = items.first().category,
                    expenseOrderCount = items.size,
                    totalExpenseCents = total,
                    averageExpenseCents = itemAverage,
                )
            }
        }
        return candidates.sortedWith(
            compareByDescending<HighFrequencySmallExpenseSnapshot> { it.expenseOrderCount }
                .thenByDescending { it.totalExpenseCents },
        ).take(10)
    }

    private fun findLargeExpenses(
        currentExpenses: List<AnalyzedRecord>,
        allRelevantRecords: List<LedgerRecordEntity>,
        month: YearMonth,
        medianExpenseCents: Long?,
        p90ExpenseCents: Long?,
    ): List<ExpenseOrderSnapshot> {
        if (medianExpenseCents == null || p90ExpenseCents == null || p90ExpenseCents <= medianExpenseCents) {
            return emptyList()
        }
        val historyStart = month.minusMonths(6)
        val historicalExpenses = allRelevantRecords.filter { record ->
            record.statisticsEffect == StatisticsEffect.ADD_EXPENSE &&
                record.statisticsMonth() >= historyStart && record.statisticsMonth() < month
        }
        return currentExpenses
            .filter { item -> item.record.amountCents >= p90ExpenseCents }
            .sortedByDescending { item -> item.record.amountCents }
            .take(20)
            .map { item ->
                val hasSimilarHistory = historicalExpenses.any { historical ->
                    val historicalMerchant = CounterpartyNormalizer.normalize(historical.counterparty)
                    val tolerance = max((item.record.amountCents * 0.10).roundToLong(), 1L)
                    item.merchantKey.isNotBlank() &&
                        historicalMerchant == item.merchantKey &&
                        kotlin.math.abs(historical.amountCents - item.record.amountCents) <= tolerance
                }
                item.toSnapshot(possibleOneTime = !hasSimilarHistory)
            }
    }

    private fun buildWorkdayWeekend(
        expenses: List<AnalyzedRecord>,
        month: YearMonth,
        elapsedEndDate: LocalDate,
    ): WorkdayWeekendSnapshot {
        var workdayCount = 0
        var weekendCount = 0
        var date = month.atDay(1)
        while (!date.isAfter(elapsedEndDate)) {
            if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
                weekendCount += 1
            } else {
                workdayCount += 1
            }
            date = date.plusDays(1)
        }
        val workdayExpenses = expenses.filter { item ->
            item.dateTime.dayOfWeek != DayOfWeek.SATURDAY &&
                item.dateTime.dayOfWeek != DayOfWeek.SUNDAY
        }
        val weekendExpenses = expenses.filter { item ->
            item.dateTime.dayOfWeek == DayOfWeek.SATURDAY ||
                item.dateTime.dayOfWeek == DayOfWeek.SUNDAY
        }
        return WorkdayWeekendSnapshot(
            workday = buildPeriod("工作日", workdayExpenses, workdayCount),
            weekend = buildPeriod("周末", weekendExpenses, weekendCount),
        )
    }

    private fun buildTimeStatistics(expenses: List<AnalyzedRecord>): TimeStatisticsSnapshot {
        val available = expenses.isNotEmpty() && expenses.any { item ->
            item.dateTime.toLocalTime() != LocalTime.MIDNIGHT
        }
        if (!available) {
            return TimeStatisticsSnapshot(false, emptyList(), null)
        }
        val bucketDefinitions = listOf(
            "06:00-11:00" to { hour: Int -> hour in 6..10 },
            "11:00-14:00" to { hour: Int -> hour in 11..13 },
            "14:00-18:00" to { hour: Int -> hour in 14..17 },
            "18:00-22:00" to { hour: Int -> hour in 18..21 },
            "22:00-06:00" to { hour: Int -> hour >= 22 || hour < 6 },
        )
        val buckets = mutableListOf<TimeBucketSnapshot>()
        for ((label, predicate) in bucketDefinitions) {
            val items = expenses.filter { item -> predicate(item.dateTime.hour) }
            buckets += TimeBucketSnapshot(
                label = label,
                totalExpenseCents = items.sumOf { item -> item.record.amountCents },
                expenseOrderCount = items.size,
            )
        }
        val nightItems = expenses.filter { item -> item.dateTime.hour >= 22 || item.dateTime.hour < 6 }
        val nightCategories = nightItems
            .groupBy { item -> item.category }
            .map { (category, items) ->
                CategoryAmountSnapshot(category, items.sumOf { item -> item.record.amountCents })
            }
            .sortedByDescending { item -> item.totalExpenseCents }
        val nightMerchants = nightItems
            .groupBy { item -> item.merchantKey }
            .map { (_, items) ->
                MerchantAmountSnapshot(items.first().merchant, items.sumOf { item -> item.record.amountCents })
            }
            .sortedByDescending { item -> item.totalExpenseCents }
        return TimeStatisticsSnapshot(
            available = true,
            buckets = buckets,
            nightExpense = NightExpenseSnapshot(
                totalExpenseCents = nightItems.sumOf { item -> item.record.amountCents },
                expenseOrderCount = nightItems.size,
                categories = nightCategories.take(10),
                merchants = nightMerchants.take(10),
            ),
        )
    }

    private fun buildMonthPeriods(
        expenses: List<AnalyzedRecord>,
        month: YearMonth,
        elapsedEndDate: LocalDate,
    ): List<PeriodStatisticSnapshot> {
        val definitions = listOf(
            Triple("月初（1-10日）", 1, 10),
            Triple("月中（11-20日）", 11, 20),
            Triple("月末（21日以后）", 21, month.lengthOfMonth()),
        )
        return definitions.map { (label, startDay, endDay) ->
            val lastElapsedDay = minOf(endDay, elapsedEndDate.dayOfMonth)
            val calendarDayCount = (lastElapsedDay - startDay + 1).coerceAtLeast(0)
            val items = expenses.filter { item -> item.dateTime.dayOfMonth in startDay..endDay }
            buildPeriod(label, items, calendarDayCount)
        }
    }

    private fun buildHistoricalStatistics(
        relevantRecords: List<LedgerRecordEntity>,
        currentExpenses: List<AnalyzedRecord>,
        month: YearMonth,
        observedHistoryMonths: List<YearMonth>,
        recordsByMonth: Map<YearMonth, List<LedgerRecordEntity>>,
        medianExpenseCents: Long?,
        categoryNames: Map<Long, String>,
    ): HistoricalStatisticsSnapshot {
        val historyPoints = observedHistoryMonths.map { historicalMonth ->
            val monthRecords = recordsByMonth[historicalMonth].orEmpty()
            val expenseAmounts = monthRecords
                .filter { record -> record.statisticsEffect == StatisticsEffect.ADD_EXPENSE }
                .map { record -> record.amountCents }
            MonthlyHistoryPointSnapshot(
                month = historicalMonth.toString(),
                netExpenseCents = netExpense(monthRecords),
                averageExpenseCents = average(expenseAmounts),
            )
        }
        val currentCategoryAmounts = currentExpenses
            .groupBy { item -> item.category }
            .mapValues { (_, items) -> items.sumOf { item -> item.record.amountCents } }
        val historicalCategoryAmounts = mutableMapOf<String, MutableMap<YearMonth, Long>>()
        for (historicalMonth in observedHistoryMonths) {
            val monthExpenses = recordsByMonth[historicalMonth].orEmpty().filter { record ->
                record.statisticsEffect == StatisticsEffect.ADD_EXPENSE
            }
            val amounts = monthExpenses.groupBy { record -> categoryNames[record.categoryId] ?: "未分类" }
            for ((category, items) in amounts) {
                historicalCategoryAmounts.getOrPut(category) { mutableMapOf() }[historicalMonth] =
                    items.sumOf { record -> record.amountCents }
            }
        }
        val categoryNamesInScope = (currentCategoryAmounts.keys + historicalCategoryAmounts.keys).distinct()
        val categoryHistory = mutableListOf<HistoricalCategorySnapshot>()
        if (observedHistoryMonths.size >= 3) {
            for (category in categoryNamesInScope) {
                val values = observedHistoryMonths.map { historicalMonth ->
                    historicalCategoryAmounts[category]?.get(historicalMonth) ?: 0L
                }
                val historicalAverage = average(values) ?: 0L
                val current = currentCategoryAmounts[category] ?: 0L
                categoryHistory += HistoricalCategorySnapshot(
                    category = category,
                    currentExpenseCents = current,
                    historicalAverageCents = historicalAverage,
                    historicalMedianCents = percentile(values.sorted(), 0.50) ?: 0L,
                    historicalMinCents = values.minOrNull() ?: 0L,
                    historicalMaxCents = values.maxOrNull() ?: 0L,
                    historicalStandardDeviationCents = standardDeviation(values),
                    currentChangeFromAverageCents = current - historicalAverage,
                )
            }
        }

        val anomalies = mutableListOf<HistoricalAnomalySnapshot>()
        if (observedHistoryMonths.size >= 3) {
            for (category in categoryHistory) {
                if (isHistoricalAnomaly(
                        current = category.currentExpenseCents,
                        historicalAverage = category.historicalAverageCents,
                        standardDeviation = category.historicalStandardDeviationCents,
                        minimumZeroDeviationIncrease = medianExpenseCents ?: 0L,
                    )
                ) {
                    anomalies += HistoricalAnomalySnapshot(
                        type = "category",
                        name = category.category,
                        currentAmountCents = category.currentExpenseCents,
                        historicalAverageCents = category.historicalAverageCents,
                        historicalStandardDeviationCents = category.historicalStandardDeviationCents,
                        increaseCents = category.currentChangeFromAverageCents,
                    )
                }
            }
            anomalies += findMerchantAnomalies(
                relevantRecords,
                month,
                observedHistoryMonths,
                medianExpenseCents ?: 0L,
            )
        }

        val baseline = if (observedHistoryMonths.size >= 3) {
            val historicalNetAmounts = historyPoints.map { point -> point.netExpenseCents }.sorted()
            val normal = percentile(historicalNetAmounts, 0.50) ?: 0L
            val currentNet = netExpense(recordsByMonth[month].orEmpty())
            val contributors = categoryHistory
                .filter { item -> item.currentChangeFromAverageCents > 0L }
                .sortedByDescending { item -> item.currentChangeFromAverageCents }
                .map { item -> CategoryAmountSnapshot(item.category, item.currentChangeFromAverageCents) }
            BaselineSnapshot(
                normalMonthlyExpenseCents = normal,
                currentDifferenceCents = currentNet - normal,
                positiveCategoryContributors = contributors,
            )
        } else {
            null
        }

        val trendMonths = observedHistoryMonths + month
        val trend = if (trendMonths.size >= 3) {
            val totalValues = trendMonths.map { trendMonth ->
                netExpense(recordsByMonth[trendMonth].orEmpty())
            }
            val averageValues = trendMonths.map { trendMonth ->
                val values = recordsByMonth[trendMonth].orEmpty()
                    .filter { record -> record.statisticsEffect == StatisticsEffect.ADD_EXPENSE }
                    .map { record -> record.amountCents }
                average(values) ?: 0L
            }
            val topCategories = currentCategoryAmounts.entries
                .sortedByDescending { entry -> entry.value }
                .take(5)
                .map { entry -> entry.key }
            TrendSnapshot(
                totalExpense = classifyTrend(totalValues),
                averageExpense = classifyTrend(averageValues),
                categoryTrends = topCategories.map { category ->
                    val values = trendMonths.map { trendMonth ->
                        if (trendMonth == month) {
                            currentCategoryAmounts[category] ?: 0L
                        } else {
                            historicalCategoryAmounts[category]?.get(trendMonth) ?: 0L
                        }
                    }
                    CategoryTrendSnapshot(category, classifyTrend(values))
                },
            )
        } else {
            null
        }
        return HistoricalStatisticsSnapshot(
            availableMonthCount = observedHistoryMonths.size,
            months = historyPoints,
            categories = categoryHistory.sortedByDescending { item -> item.currentExpenseCents },
            anomalies = anomalies.sortedByDescending { item -> item.increaseCents }.take(20),
            baseline = baseline,
            trend = trend,
        )
    }

    private fun findMerchantAnomalies(
        relevantRecords: List<LedgerRecordEntity>,
        month: YearMonth,
        observedHistoryMonths: List<YearMonth>,
        medianExpenseCents: Long,
    ): List<HistoricalAnomalySnapshot> {
        val expenseRecords = relevantRecords.filter { record ->
            record.statisticsEffect == StatisticsEffect.ADD_EXPENSE &&
                CounterpartyNormalizer.normalize(record.counterparty).isNotBlank()
        }
        val currentAmounts = expenseRecords
            .filter { record -> record.statisticsMonth() == month }
            .groupBy { record -> CounterpartyNormalizer.normalize(record.counterparty) }
            .mapValues { (_, items) -> items.sumOf { item -> item.amountCents } }
        val displayNames = expenseRecords.associate { record ->
            CounterpartyNormalizer.normalize(record.counterparty) to normalizedText(record.counterparty, 80).orEmpty()
        }
        val historicalAmounts = mutableMapOf<Pair<YearMonth, String>, Long>()
        for (record in expenseRecords) {
            val recordMonth = record.statisticsMonth()
            if (recordMonth !in observedHistoryMonths) {
                continue
            }
            val merchantKey = CounterpartyNormalizer.normalize(record.counterparty)
            val key = recordMonth to merchantKey
            historicalAmounts[key] = historicalAmounts.getOrDefault(key, 0L) + record.amountCents
        }
        val result = mutableListOf<HistoricalAnomalySnapshot>()
        for ((merchantKey, current) in currentAmounts) {
            val values = observedHistoryMonths.map { historicalMonth ->
                historicalAmounts[historicalMonth to merchantKey] ?: 0L
            }
            val historicalAverage = average(values) ?: 0L
            val deviation = standardDeviation(values)
            if (isHistoricalAnomaly(current, historicalAverage, deviation, medianExpenseCents)) {
                result += HistoricalAnomalySnapshot(
                    type = "merchant",
                    name = displayNames[merchantKey].orEmpty().ifBlank { "未知商家" },
                    currentAmountCents = current,
                    historicalAverageCents = historicalAverage,
                    historicalStandardDeviationCents = deviation,
                    increaseCents = current - historicalAverage,
                )
            }
        }
        return result
    }

    private fun isHistoricalAnomaly(
        current: Long,
        historicalAverage: Long,
        standardDeviation: Long,
        minimumZeroDeviationIncrease: Long,
    ): Boolean {
        if (current <= historicalAverage) {
            return false
        }
        return if (standardDeviation > 0L) {
            current > historicalAverage + standardDeviation * 2
        } else {
            current - historicalAverage >= minimumZeroDeviationIncrease
        }
    }

    private fun findPossibleDuplicates(expenses: List<AnalyzedRecord>): List<DuplicateCandidateSnapshot> {
        val groups = expenses
            .filter { item -> item.merchantKey.isNotBlank() }
            .groupBy { item ->
                Triple(item.merchantKey, item.record.amountCents, item.record.paymentChannel.name)
            }
        val candidates = mutableListOf<DuplicateCandidateSnapshot>()
        for ((_, group) in groups) {
            val sorted = group.sortedBy { item -> item.record.transactionTimeMillis }
            for (index in 1 until sorted.size) {
                val first = sorted[index - 1]
                val second = sorted[index]
                val gapMillis = second.record.transactionTimeMillis - first.record.transactionTimeMillis
                if (gapMillis in 0..DUPLICATE_WINDOW_MILLIS) {
                    candidates += DuplicateCandidateSnapshot(
                        merchant = second.merchant,
                        amountCents = second.record.amountCents,
                        paymentChannel = second.record.paymentChannel.name.lowercase(),
                        firstTransactionTime = first.formattedTime,
                        secondTransactionTime = second.formattedTime,
                        gapMinutes = gapMillis / 60_000,
                    )
                }
            }
        }
        return candidates.sortedBy { item -> item.firstTransactionTime }.take(20)
    }

    private fun findPossibleRecurringPayments(
        relevantRecords: List<LedgerRecordEntity>,
        categoryNames: Map<Long, String>,
        month: YearMonth,
    ): List<RecurringPaymentCandidateSnapshot> {
        val startMonth = month.minusMonths(5)
        val expenses = relevantRecords
            .filter { record ->
                record.statisticsEffect == StatisticsEffect.ADD_EXPENSE &&
                    record.statisticsMonth() >= startMonth && record.statisticsMonth() <= month &&
                    CounterpartyNormalizer.normalize(record.counterparty).isNotBlank()
            }
            .map { record -> record.toAnalyzedRecord(categoryNames) }
        val candidates = mutableListOf<RecurringPaymentCandidateSnapshot>()
        for ((_, items) in expenses.groupBy { item -> item.merchantKey }) {
            val occurrenceMonths = items.map { item -> item.record.statisticsMonth() }.distinct().sorted()
            if (occurrenceMonths.size < 3) {
                continue
            }
            val amounts = items.map { item -> item.record.amountCents }.sorted()
            val typicalAmount = percentile(amounts, 0.50) ?: continue
            val tolerance = max((typicalAmount * 0.05).roundToLong(), 100L)
            if ((amounts.last() - amounts.first()) > tolerance) {
                continue
            }
            val latest = items.maxByOrNull { item -> item.record.transactionTimeMillis } ?: continue
            candidates += RecurringPaymentCandidateSnapshot(
                merchant = latest.merchant,
                typicalAmountCents = typicalAmount,
                occurrenceMonths = occurrenceMonths.map { value -> value.toString() },
                occurrenceCount = items.size,
                latestPaymentDate = latest.dateTime.toLocalDate().toString(),
            )
        }
        return candidates.sortedWith(
            compareByDescending<RecurringPaymentCandidateSnapshot> { it.occurrenceMonths.size }
                .thenByDescending { it.typicalAmountCents },
        ).take(20)
    }

    private fun buildConcentration(
        expenses: List<AnalyzedRecord>,
        grossExpenseCents: Long,
    ): ConcentrationSnapshot {
        val amounts = expenses.map { item -> item.record.amountCents }.sortedDescending()
        val merchantTotals = expenses
            .groupBy { item -> item.merchantKey }
            .values
            .map { items -> items.sumOf { item -> item.record.amountCents } }
            .sortedDescending()
        return ConcentrationSnapshot(
            top1OrderSharePercent = percent(amounts.take(1).sum(), grossExpenseCents),
            top3OrderSharePercent = percent(amounts.take(3).sum(), grossExpenseCents),
            top5OrderSharePercent = percent(amounts.take(5).sum(), grossExpenseCents),
            top10OrderSharePercent = percent(amounts.take(10).sum(), grossExpenseCents),
            top5MerchantSharePercent = percent(merchantTotals.take(5).sum(), grossExpenseCents),
        )
    }

    private fun classifyTrend(values: List<Long>): String {
        if (values.size < 3) {
            return "insufficient"
        }
        val averageValue = values.average()
        if (averageValue <= 0.0) {
            return "stable"
        }
        val xAverage = (values.indices).average()
        var numerator = 0.0
        var denominator = 0.0
        for (index in values.indices) {
            numerator += (index - xAverage) * (values[index] - averageValue)
            denominator += (index - xAverage) * (index - xAverage)
        }
        val slope = if (denominator == 0.0) 0.0 else numerator / denominator
        val normalizedSlope = kotlin.math.abs(slope) / averageValue
        if (normalizedSlope < 0.05) {
            return "stable"
        }
        var matchingChanges = 0
        var nonZeroChanges = 0
        for (index in 1 until values.size) {
            val change = values[index] - values[index - 1]
            if (change == 0L) {
                continue
            }
            nonZeroChanges += 1
            if ((slope > 0 && change > 0) || (slope < 0 && change < 0)) {
                matchingChanges += 1
            }
        }
        val directionAgreement = if (nonZeroChanges == 0) {
            0.0
        } else {
            matchingChanges.toDouble() / nonZeroChanges
        }
        if (directionAgreement < 0.60) {
            return "stable"
        }
        return if (slope > 0) "up" else "down"
    }

    private fun LedgerRecordEntity.toAnalyzedRecord(
        categoryNames: Map<Long, String>,
    ): AnalyzedRecord {
        val merchantText = normalizedText(counterparty, 80)
        return AnalyzedRecord(
            record = this,
            category = categoryNames[categoryId] ?: "未分类",
            merchant = merchantText ?: "未知商家",
            merchantKey = CounterpartyNormalizer.normalize(merchantText),
            dateTime = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(transactionTimeMillis),
                zoneId,
            ),
        )
    }

    private fun AnalyzedRecord.toSnapshot(possibleOneTime: Boolean? = null): ExpenseOrderSnapshot {
        return ExpenseOrderSnapshot(
            transactionTime = formattedTime,
            amountCents = record.amountCents,
            effect = if (record.statisticsEffect == StatisticsEffect.ADD_EXPENSE) {
                "expense"
            } else {
                "refund"
            },
            merchant = merchant.takeUnless { value -> value == "未知商家" },
            category = category,
            paymentChannel = record.paymentChannel.name.lowercase(),
            note = normalizedText(record.note, 200),
            possibleOneTime = possibleOneTime,
        )
    }

    private fun LedgerRecordEntity.statisticsMonth(): YearMonth {
        val dateTime = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(statisticsTimeMillis),
            zoneId,
        )
        return YearMonth.from(dateTime)
    }

    private fun elapsedEndDate(month: YearMonth, nowMillis: Long): LocalDate {
        val today = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(nowMillis),
            zoneId,
        ).toLocalDate()
        return when {
            YearMonth.from(today) < month -> month.atDay(1)
            YearMonth.from(today) == month -> today.coerceAtMost(month.atEndOfMonth())
            else -> month.atEndOfMonth()
        }
    }

    private fun netExpense(records: List<LedgerRecordEntity>): Long {
        var result = 0L
        for (record in records) {
            when (record.statisticsEffect) {
                StatisticsEffect.ADD_EXPENSE -> result += record.amountCents
                StatisticsEffect.SUBTRACT_EXPENSE -> result -= record.amountCents
                else -> Unit
            }
        }
        return result
    }

    private fun buildPeriod(
        label: String,
        items: List<AnalyzedRecord>,
        calendarDayCount: Int,
    ): PeriodStatisticSnapshot {
        val total = items.sumOf { item -> item.record.amountCents }
        return PeriodStatisticSnapshot(
            label = label,
            totalExpenseCents = total,
            expenseOrderCount = items.size,
            calendarDayCount = calendarDayCount,
            averagePerCalendarDayCents = divide(total, calendarDayCount),
        )
    }

    private fun average(values: List<Long>): Long? {
        if (values.isEmpty()) {
            return null
        }
        return values.average().roundToLong()
    }

    private fun divide(value: Long, divisor: Int): Long? {
        if (divisor <= 0) {
            return null
        }
        return (value.toDouble() / divisor).roundToLong()
    }

    private fun percentile(sortedValues: List<Long>, percentile: Double): Long? {
        if (sortedValues.isEmpty()) {
            return null
        }
        val rank = ceil(percentile * sortedValues.size).toInt().coerceIn(1, sortedValues.size)
        return sortedValues[rank - 1]
    }

    private fun standardDeviation(values: List<Long>): Long {
        if (values.isEmpty()) {
            return 0L
        }
        val averageValue = values.average()
        val variance = values.sumOf { value ->
            val difference = value - averageValue
            difference * difference
        } / values.size
        return sqrt(variance).roundToLong()
    }

    private fun percent(part: Long, total: Long): Double {
        if (total <= 0L) {
            return 0.0
        }
        return (part * 100.0 / total).roundTo(2)
    }

    private fun Double.roundTo(decimalPlaces: Int): Double {
        val factor = when (decimalPlaces) {
            0 -> 1.0
            1 -> 10.0
            else -> 100.0
        }
        return kotlin.math.round(this * factor) / factor
    }

    private fun normalizedText(value: String?, maxLength: Int): String? {
        val normalized = value
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.take(maxLength)
            .orEmpty()
        return normalized.ifBlank { null }
    }

    private data class AnalyzedRecord(
        val record: LedgerRecordEntity,
        val category: String,
        val merchant: String,
        val merchantKey: String,
        val dateTime: ZonedDateTime,
    ) {
        val formattedTime: String
            get() = dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    companion object {
        private const val DUPLICATE_WINDOW_MILLIS = 10 * 60 * 1_000L
    }
}
