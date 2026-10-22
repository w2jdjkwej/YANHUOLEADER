package com.yanhuozhang.offline.analysis

import com.yanhuozhang.offline.settings.DeepSeekModel
import com.yanhuozhang.offline.settings.DeepSeekThinkingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonObject

class DeepSeekMonthlyAnalysisClientTest {
    private val client = DeepSeekMonthlyAnalysisClient()
    private val snapshot = MonthlyExpenseSnapshot(
        payload = payload(),
        fingerprint = "fingerprint",
    )

    @Test
    fun `request uses selected model and complete expense and refund data`() {
        val request = client.buildRequest(
            snapshot = snapshot,
            model = DeepSeekModel.PRO,
            thinkingMode = DeepSeekThinkingMode.ENABLED,
        )

        assertTrue(request.contains("\"model\":\"deepseek-v4-pro\""))
        assertTrue(request.contains("\"thinking\":{\"type\":\"enabled\"}"))
        assertTrue(request.contains("\\\"effect\\\":\\\"expense\\\""))
        assertTrue(request.contains("\\\"effect\\\":\\\"refund\\\""))
        assertTrue(request.contains("测试餐厅"))
        assertTrue(request.contains("退款说明"))
        assertFalse(request.contains("rawText"))
        assertFalse(request.contains("sourceFingerprint"))
        assertFalse(request.contains("recordId"))
        assertTrue(request.contains("potentialSavings"))
    }

    @Test
    fun `valid structured report is parsed and limited`() {
        val response = responseWithContent(
            """
            {
              "headline":"购物是本月主要变化。",
              "overallAssessment":"日常消费基本稳定。",
              "keyFindings":[
                {"title":"购物增长","description":"两笔大额消费贡献主要增量","evidence":"购物较历史均值增加1000元","importance":"urgent"},
                {"title":"餐饮稳定","description":"与历史接近","evidence":"连续三月约1200元","importance":"low"},
                {"title":"夜间消费","description":"夜间购物增加","evidence":"22点后7笔","importance":"medium"},
                {"title":"多余发现","description":"不应保留","evidence":"","importance":"high"}
              ],
              "potentialSavings":{"minCents":30000,"maxCents":50000,"explanation":"来自高频小额与夜间购物"},
              "nextMonthSuggestions":["减少临时购物频率","确认疑似订阅","观察夜间购物","多余建议"]
            }
            """.trimIndent(),
        )

        val result = client.parseResponse(response, grossExpenseCents = 100_000)

        assertTrue(result is MonthlyAnalysisApiResult.Success)
        val content = (result as MonthlyAnalysisApiResult.Success).content
        assertEquals(3, content.keyFindings.size)
        assertEquals("medium", content.keyFindings.first().importance)
        assertEquals(3, content.nextMonthSuggestions.size)
        assertEquals(30_000L, content.potentialSavings?.minCents)
        assertTrue(content.categoryInsights.isEmpty())
        assertNull(content.trendAnalysis)
    }

    @Test
    fun `markdown fenced json is accepted`() {
        val response = responseWithContent(
            """
            ```json
            {
              "headline":"本月消费平稳。",
              "overallAssessment":"没有明显异常。"
            }
            ```
            """.trimIndent(),
        )

        val result = client.parseResponse(response, grossExpenseCents = 100_000)

        assertTrue(result is MonthlyAnalysisApiResult.Success)
    }

    @Test
    fun `invalid savings range is rejected`() {
        val response = responseWithContent(
            """
            {
              "headline":"本月消费分析。",
              "overallAssessment":"数据有效。",
              "potentialSavings":{"minCents":50000,"maxCents":200000,"explanation":"超出本月支出"}
            }
            """.trimIndent(),
        )

        val result = client.parseResponse(response, grossExpenseCents = 100_000)

        assertTrue(result is MonthlyAnalysisApiResult.Failure)
    }

    @Test
    fun `invalid json is rejected`() {
        val result = client.parseResponse(responseWithContent("not-json"), 100_000)

        assertTrue(result is MonthlyAnalysisApiResult.Failure)
    }

    @Test
    fun `thinking output limit is reported before decoding empty content`() {
        val response = """{"choices":[{"finish_reason":"length","message":{"content":"","reasoning_content":"thinking"}}]}"""

        val result = client.parseResponse(response, 100_000)

        assertTrue(result is MonthlyAnalysisApiResult.Failure)
        assertTrue((result as MonthlyAnalysisApiResult.Failure).reason.contains("截断"))
    }

    @Test
    fun `truncated completion is rejected even when core json is complete`() {
        val response = responseWithContent("""{"headline":"部分分析"}""")
            .replace("\"message\":", "\"finish_reason\":\"length\",\"message\":")

        val result = client.parseResponse(response, 100_000)

        assertTrue(result is MonthlyAnalysisApiResult.Failure)
        assertTrue((result as MonthlyAnalysisApiResult.Failure).reason.contains("截断"))
    }

    @Test
    fun `empty completed response reports missing body`() {
        val result = client.parseResponse(responseWithContent(""), 100_000)

        assertTrue(result is MonthlyAnalysisApiResult.Failure)
        assertTrue((result as MonthlyAnalysisApiResult.Failure).reason.contains("正文为空"))
    }

    @Test
    fun `monthly reports leave output budget to provider in both modes`() {
        val standardRequest = MonthlyAnalysisJson.format.parseToJsonElement(
            client.buildRequest(snapshot, DeepSeekModel.FLASH, DeepSeekThinkingMode.DISABLED),
        ).jsonObject
        val thinkingRequest = MonthlyAnalysisJson.format.parseToJsonElement(
            client.buildRequest(snapshot, DeepSeekModel.FLASH, DeepSeekThinkingMode.ENABLED),
        ).jsonObject

        assertFalse(standardRequest.containsKey("max_tokens"))
        assertFalse(thinkingRequest.containsKey("max_tokens"))
    }

    private fun responseWithContent(content: String): String {
        val escaped = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"choices":[{"message":{"content":"$escaped"}}]}"""
    }

    private fun payload(): MonthlyAnalysisPayload {
        val expense = ExpenseOrderSnapshot(
            transactionTime = "2026-07-15T12:00:00+08:00",
            amountCents = 10_000,
            effect = "expense",
            merchant = "测试餐厅",
            category = "餐饮",
            paymentChannel = "wechat",
            note = "午餐",
        )
        val refund = expense.copy(
            transactionTime = "2026-07-16T12:00:00+08:00",
            amountCents = 1_000,
            effect = "refund",
            note = "退款说明",
        )
        return MonthlyAnalysisPayload(
            month = "2026-07",
            generatedAsOf = "2026-07-31T23:30:00+08:00",
            summary = MonthlySummarySnapshot(
                grossExpenseCents = 10_000,
                refundCents = 1_000,
                netExpenseCents = 9_000,
                expenseOrderCount = 1,
                averageExpenseCents = 10_000,
                largestExpense = expense,
                spendingDayCount = 1,
                averagePerSpendingDayCents = 9_000,
                elapsedCalendarDayCount = 31,
                averagePerCalendarDayCents = 290,
                previousMonthNetExpenseCents = null,
                monthOverMonthChangeCents = null,
                monthOverMonthChangePercent = null,
            ),
            categoryStatistics = emptyList(),
            merchantStatistics = MerchantStatisticsSnapshot(emptyList(), emptyList()),
            behaviorStatistics = BehaviorStatisticsSnapshot(
                medianExpenseCents = 10_000,
                p75ExpenseCents = 10_000,
                p90ExpenseCents = 10_000,
                highFrequencySmallExpenses = emptyList(),
                workdayWeekend = WorkdayWeekendSnapshot(
                    PeriodStatisticSnapshot("工作日", 10_000, 1, 23, 435),
                    PeriodStatisticSnapshot("周末", 0, 0, 8, 0),
                ),
                timeStatistics = TimeStatisticsSnapshot(false, emptyList(), null),
                monthPeriods = emptyList(),
                expenseExcludingLargeCandidatesCents = 10_000,
            ),
            historicalStatistics = HistoricalStatisticsSnapshot(
                availableMonthCount = 0,
                months = emptyList(),
                categories = emptyList(),
                anomalies = emptyList(),
                baseline = null,
                trend = null,
            ),
            possibleDuplicates = emptyList(),
            possibleRecurringPayments = emptyList(),
            largeExpenseCandidates = emptyList(),
            concentration = ConcentrationSnapshot(100.0, 100.0, 100.0, 100.0, 100.0),
            orders = listOf(expense, refund),
        )
    }
}
