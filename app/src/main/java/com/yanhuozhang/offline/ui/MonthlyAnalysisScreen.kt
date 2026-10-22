package com.yanhuozhang.offline.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanhuozhang.offline.analysis.AiInsight
import com.yanhuozhang.offline.analysis.MonthlyAiAnalysis
import com.yanhuozhang.offline.analysis.MonthlyAnalysisPayload
import com.yanhuozhang.offline.data.MonthlyAnalysisReportEntity
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthlyAnalysisScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.monthlyAnalysisDetailState.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("月度分析") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = YanHuoDimens.ScreenPadding,
                end = YanHuoDimens.ScreenPadding,
                top = 8.dp,
                bottom = 36.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "month-selector") {
                MonthSelector(
                    months = state.availableMonths,
                    selectedMonth = state.selectedMonth,
                    onSelected = viewModel::selectMonthlyAnalysisMonth,
                )
            }
            val local = state.localAnalysis
            if (local == null) {
                item(key = "empty") {
                    MessagePanel(
                        title = "这个月还没有消费或退款记录",
                        description = "有可分析账单后，本地统计会先显示在这里。",
                    )
                }
            } else {
                item(key = "summary-header") { SectionHeader("本月总览") }
                item(key = "summary") { SummaryPanel(local) }
                item(key = "ai-header") { SectionHeader("AI 重点结论") }
                item(key = "ai") {
                    AiReportPanel(
                        ai = state.aiAnalysis,
                        legacyReport = state.report,
                        reportIsCurrent = state.reportIsCurrent,
                        deepSeekEnabled = state.deepSeekEnabled,
                        running = state.running,
                        onGenerate = viewModel::analyzeSelectedMonth,
                    )
                }
                if (state.aiAnalysis?.keyFindings?.isNotEmpty() == true) {
                    item(key = "findings-header") { SectionHeader("关键发现") }
                    items(
                        count = state.aiAnalysis!!.keyFindings.size,
                        key = { index -> "finding-$index" },
                    ) { index ->
                        val finding = state.aiAnalysis!!.keyFindings[index]
                        InsightPanel(
                            title = finding.title,
                            description = finding.description,
                            evidence = finding.evidence,
                            emphasized = finding.importance == "high",
                        )
                    }
                }
                item(key = "category-header") { SectionHeader("分类消费") }
                item(key = "categories") { CategoryStatisticsPanel(local, state.aiAnalysis) }
                item(key = "merchant-header") { SectionHeader("商家消费") }
                item(key = "merchants") { MerchantStatisticsPanel(local) }
                item(key = "behavior-header") { SectionHeader("消费行为") }
                item(key = "behavior") { BehaviorPanel(local, state.aiAnalysis) }
                item(key = "abnormal-header") { SectionHeader("异常与周期候选") }
                item(key = "abnormal") { AbnormalPanel(local, state.aiAnalysis) }
                item(key = "trend-header") { SectionHeader("历史趋势") }
                item(key = "trend") { TrendPanel(local, state.aiAnalysis) }
                item(key = "savings-header") { SectionHeader("可优化金额") }
                item(key = "savings") { SavingsPanel(state.aiAnalysis) }
                item(key = "suggestions-header") { SectionHeader("下月建议") }
                item(key = "suggestions") { SuggestionsPanel(state.aiAnalysis) }
            }
        }
    }
}

@Composable
private fun MonthSelector(
    months: List<YearMonth>,
    selectedMonth: YearMonth,
    onSelected: (YearMonth) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (month in months) {
            FilterChip(
                selected = month == selectedMonth,
                onClick = { onSelected(month) },
                label = { Text("${month.year}年${month.monthValue}月") },
            )
        }
    }
}

@Composable
private fun SummaryPanel(local: MonthlyAnalysisPayload) {
    val summary = local.summary
    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("净支出", color = MaterialTheme.colorScheme.onSurfaceVariant)
            AmountText(cents = summary.netExpenseCents, style = MaterialTheme.typography.headlineMedium)
            InfoRow("消费总额", formatMoney(summary.grossExpenseCents))
            InfoRow("退款调整", formatMoney(summary.refundCents))
            InfoRow("消费笔数", "${summary.expenseOrderCount} 笔")
            InfoRow("平均单笔", summary.averageExpenseCents?.let(::formatMoney) ?: "暂无")
            InfoRow("消费天数", "${summary.spendingDayCount} 天")
            InfoRow("日均净支出", summary.averagePerCalendarDayCents?.let(::formatMoney) ?: "暂无")
            val comparison = when {
                summary.previousMonthNetExpenseCents == null -> "暂无上月数据"
                summary.monthOverMonthChangePercent == null -> "上月净支出为 0，无法计算百分比"
                else -> {
                    val direction = if ((summary.monthOverMonthChangeCents ?: 0L) >= 0) "增加" else "减少"
                    "$direction ${formatMoney(kotlin.math.abs(summary.monthOverMonthChangeCents ?: 0L))}（${kotlin.math.abs(summary.monthOverMonthChangePercent)}%）"
                }
            }
            InfoRow("相比上月", comparison)
            summary.largestExpense?.let { largest ->
                Text(
                    text = "最大单笔：${formatMoney(largest.amountCents)} · " +
                        "${largest.merchant ?: "未知商家"} · ${largest.category}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AiReportPanel(
    ai: MonthlyAiAnalysis?,
    legacyReport: MonthlyAnalysisReportEntity?,
    reportIsCurrent: Boolean,
    deepSeekEnabled: Boolean,
    running: Boolean,
    onGenerate: () -> Unit,
) {
    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                ai != null -> {
                    Text(ai.headline, style = MaterialTheme.typography.titleLarge)
                    if (ai.overallAssessment.isNotBlank()) {
                        Text(ai.overallAssessment, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                legacyReport != null -> {
                    Text(legacyReport.summary, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "这是旧版聚合月报；重新生成后可查看完整分析。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    listOfNotNull(
                        legacyReport.observation1,
                        legacyReport.observation2,
                        legacyReport.observation3,
                    ).forEach { observation -> Text("• $observation") }
                    legacyReport.suggestion?.let { suggestion -> Text("建议：$suggestion") }
                    legacyReport.lightComment?.let { comment -> Text(comment) }
                }
                else -> {
                    Text("尚未生成 AI 分析", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "本地统计已经可用。生成时会向 DeepSeek 发送该月全部消费与退款明细。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (legacyReport != null && !reportIsCurrent) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "账单已发生变化，当前报告可能不是最新。",
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Button(
                onClick = onGenerate,
                enabled = deepSeekEnabled && !running,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(if (running) "正在分析…" else if (legacyReport == null) "生成本月分析" else "重新生成")
            }
            if (!deepSeekEnabled) {
                Text(
                    "请先在设置中启用 DeepSeek 并填写 API Key。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CategoryStatisticsPanel(
    local: MonthlyAnalysisPayload,
    ai: MonthlyAiAnalysis?,
) {
    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            for (category in local.categoryStatistics) {
                Text(category.category, style = MaterialTheme.typography.titleSmall)
                InfoRow(
                    "净支出 / 占毛支出",
                    "${formatMoney(category.netExpenseCents)} / ${category.shareOfGrossPercent}%",
                )
                InfoRow(
                    "笔数 / 平均",
                    "${category.expenseOrderCount} 笔 / " +
                        (category.averageExpenseCents?.let(::formatMoney) ?: "暂无"),
                )
                ai?.categoryInsights
                    ?.firstOrNull { insight -> insight.category == category.category }
                    ?.let { insight ->
                        Text(
                            insight.insight,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun MerchantStatisticsPanel(local: MonthlyAnalysisPayload) {
    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("消费金额 TOP 10", style = MaterialTheme.typography.titleMedium)
            for ((index, merchant) in local.merchantStatistics.topByAmount.withIndex()) {
                InfoRow("${index + 1}. ${merchant.merchant}", formatMoney(merchant.totalExpenseCents))
            }
            Spacer(Modifier.height(8.dp))
            Text("消费频率 TOP 10", style = MaterialTheme.typography.titleMedium)
            for ((index, merchant) in local.merchantStatistics.topByFrequency.withIndex()) {
                InfoRow(
                    "${index + 1}. ${merchant.merchant}",
                    "${merchant.expenseOrderCount} 笔 · ${formatMoney(merchant.totalExpenseCents)}",
                )
            }
        }
    }
}

@Composable
private fun BehaviorPanel(local: MonthlyAnalysisPayload, ai: MonthlyAiAnalysis?) {
    val behavior = local.behaviorStatistics
    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfoRow("金额中位数", behavior.medianExpenseCents?.let(::formatMoney) ?: "暂无")
            InfoRow("P75 / P90", listOfNotNull(
                behavior.p75ExpenseCents?.let(::formatMoney),
                behavior.p90ExpenseCents?.let(::formatMoney),
            ).joinToString(" / ").ifBlank { "暂无" })
            InfoRow(
                "剔除一次性大额候选后",
                formatMoney(behavior.expenseExcludingLargeCandidatesCents),
            )
            Text("高频小额候选", style = MaterialTheme.typography.titleMedium)
            if (behavior.highFrequencySmallExpenses.isEmpty()) {
                Text("未发现明显候选", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                for (item in behavior.highFrequencySmallExpenses) {
                    InfoRow(
                        "${item.merchant} · ${item.category}",
                        "${item.expenseOrderCount} 笔 · ${formatMoney(item.totalExpenseCents)}",
                    )
                }
            }
            InfoRow(
                "工作日",
                "${formatMoney(behavior.workdayWeekend.workday.totalExpenseCents)} · " +
                    "${behavior.workdayWeekend.workday.expenseOrderCount} 笔",
            )
            InfoRow(
                "周末",
                "${formatMoney(behavior.workdayWeekend.weekend.totalExpenseCents)} · " +
                    "${behavior.workdayWeekend.weekend.expenseOrderCount} 笔",
            )
            if (behavior.timeStatistics.available) {
                behavior.timeStatistics.nightExpense?.let { night ->
                    InfoRow("22:00 后", "${formatMoney(night.totalExpenseCents)} · ${night.expenseOrderCount} 笔")
                }
            } else {
                Text("账单缺少可靠小时精度，已跳过时段分析。", style = MaterialTheme.typography.bodySmall)
            }
            for (insight in ai?.behaviorInsights.orEmpty()) {
                CompactInsight(insight)
            }
            ai?.dailyExpenseAssessment?.let { assessment ->
                Text("日常支出判断", style = MaterialTheme.typography.titleMedium)
                Text(assessment, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AbnormalPanel(local: MonthlyAnalysisPayload, ai: MonthlyAiAnalysis?) {
    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("本地异常候选", style = MaterialTheme.typography.titleMedium)
            if (local.historicalStatistics.anomalies.isEmpty()) {
                Text("历史数据不足或未发现明显增长", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                for (item in local.historicalStatistics.anomalies.take(10)) {
                    InfoRow(
                        "${if (item.type == "category") "分类" else "商家"} · ${item.name}",
                        "+${formatMoney(item.increaseCents)}",
                    )
                }
            }
            InfoRow("疑似重复", "${local.possibleDuplicates.size} 组")
            InfoRow("疑似周期消费", "${local.possibleRecurringPayments.size} 项")
            for (item in ai?.abnormalExpenses.orEmpty()) CompactInsight(item)
            for (item in ai?.recurringPayments.orEmpty()) CompactInsight(item)
        }
    }
}

@Composable
private fun TrendPanel(local: MonthlyAnalysisPayload, ai: MonthlyAiAnalysis?) {
    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfoRow("可用历史月份", "${local.historicalStatistics.availableMonthCount} 个月")
            local.historicalStatistics.baseline?.let { baseline ->
                InfoRow("正常月度水平", formatMoney(baseline.normalMonthlyExpenseCents))
                InfoRow("本月偏离", formatMoney(baseline.currentDifferenceCents))
            }
            local.historicalStatistics.trend?.let { trend ->
                InfoRow("总支出趋势", trendLabel(trend.totalExpense))
                InfoRow("平均单笔趋势", trendLabel(trend.averageExpense))
            }
            ai?.trendAnalysis?.let { analysis ->
                Text(analysis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SavingsPanel(ai: MonthlyAiAnalysis?) {
    val savings = ai?.potentialSavings
    if (savings == null) {
        MessagePanel("暂无可靠区间", "证据不足时不会强行估算可优化金额。")
        return
    }
    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${formatMoney(savings.minCents)} ～ ${formatMoney(savings.maxCents)}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(savings.explanation, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SuggestionsPanel(ai: MonthlyAiAnalysis?) {
    val suggestions = ai?.nextMonthSuggestions.orEmpty()
    if (suggestions.isEmpty()) {
        MessagePanel("暂无针对性建议", "生成 AI 分析后会在这里显示最多三条建议。")
        return
    }
    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for ((index, suggestion) in suggestions.withIndex()) {
                Text("${index + 1}. $suggestion")
            }
            if (ai?.positiveObservations?.isNotEmpty() == true) {
                Spacer(Modifier.height(4.dp))
                Text("做得不错", style = MaterialTheme.typography.titleMedium)
                for (observation in ai.positiveObservations) Text("• $observation")
            }
            if (ai?.dataLimitations?.isNotEmpty() == true) {
                Spacer(Modifier.height(4.dp))
                Text("数据限制", style = MaterialTheme.typography.titleMedium)
                for (limitation in ai.dataLimitations) {
                    Text("• $limitation", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun InsightPanel(
    title: String,
    description: String,
    evidence: String,
    emphasized: Boolean,
) {
    YanHuoPanel(
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (emphasized) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleMedium)
            if (description.isNotBlank()) Text(description)
            if (evidence.isNotBlank()) {
                Text(
                    "依据：$evidence",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CompactInsight(insight: AiInsight) {
    if (insight.title.isNotBlank()) Text(insight.title, style = MaterialTheme.typography.titleSmall)
    if (insight.description.isNotBlank()) Text(insight.description)
    if (insight.evidence.isNotBlank()) {
        Text(
            "依据：${insight.evidence}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MessagePanel(title: String, description: String) {
    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun trendLabel(value: String): String {
    return when (value) {
        "up" -> "上涨"
        "down" -> "下降"
        "stable" -> "基本稳定"
        else -> "数据不足"
    }
}
