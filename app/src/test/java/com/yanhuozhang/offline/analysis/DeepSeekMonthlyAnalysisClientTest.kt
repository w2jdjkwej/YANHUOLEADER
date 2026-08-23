package com.yanhuozhang.offline.analysis

import com.yanhuozhang.offline.settings.DeepSeekModel
import com.yanhuozhang.offline.settings.DeepSeekThinkingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekMonthlyAnalysisClientTest {
    private val client = DeepSeekMonthlyAnalysisClient()
    private val snapshot = MonthlyExpenseSnapshot(
        monthKey = "2026-07",
        totalExpenseCents = 10_000,
        categories = listOf(
            MonthlyCategorySnapshot("餐饮", 6_000, 60.0),
            MonthlyCategorySnapshot("购物", 4_000, 40.0),
        ),
        fingerprint = "fingerprint",
    )

    @Test
    fun `request uses selected model thinking mode and aggregate data`() {
        val request = client.buildRequest(
            snapshot = snapshot,
            model = DeepSeekModel.PRO,
            thinkingMode = DeepSeekThinkingMode.ENABLED,
        )

        assertTrue(request.contains("\"model\":\"deepseek-v4-pro\""))
        assertTrue(request.contains("\"thinking\":{\"type\":\"enabled\"}"))
        assertTrue(request.contains("\"max_tokens\":1600"))
        assertTrue(request.contains("\\\"total_expense_cents\\\":10000"))
        assertTrue(request.contains("\\\"name\\\":\\\"餐饮\\\""))
        assertTrue(request.contains("幽默只能出现在 light_comment"))
    }

    @Test
    fun `valid structured report is parsed`() {
        val response = responseWithContent(
            """
            {
              "summary":"本月支出主要集中在餐饮和购物。",
              "observations":["餐饮占比为60%。","购物占比为40%。"],
              "suggestion":"可继续观察餐饮支出的变化。",
              "light_comment":"餐饮依旧稳坐支出榜首席。"
            }
            """.trimIndent(),
        )

        val result = client.parseResponse(response)

        assertTrue(result is MonthlyAnalysisApiResult.Success)
        val content = (result as MonthlyAnalysisApiResult.Success).content
        assertEquals(2, content.observations.size)
        assertEquals("餐饮依旧稳坐支出榜首席。", content.lightComment)
    }

    @Test
    fun `forbidden joke is discarded and exclamation is removed`() {
        val response = responseWithContent(
            """
            {
              "summary":"本月支出有所增加！",
              "observations":[],
              "suggestion":null,
              "light_comment":"再买就要吃土咯！"
            }
            """.trimIndent(),
        )

        val result = client.parseResponse(response)

        assertTrue(result is MonthlyAnalysisApiResult.Success)
        val content = (result as MonthlyAnalysisApiResult.Success).content
        assertEquals("本月支出有所增加。", content.summary)
        assertNull(content.lightComment)
    }

    private fun responseWithContent(content: String): String {
        val escaped = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"choices":[{"message":{"content":"$escaped"}}]}"""
    }
}
