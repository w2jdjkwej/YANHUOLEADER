package com.yanhuozhang.offline.recognition

import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.TransactionDirection
import com.yanhuozhang.offline.data.CategoryEntity
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotResponseParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 22, 14, 30).atZone(zone).toInstant().toEpochMilli()
    private val categories = listOf(
        category(id = 1, name = "餐饮", type = AccountingType.EXPENSE),
        category(id = 2, name = "工资", type = AccountingType.INCOME),
        category(id = 3, name = "停用分类", type = AccountingType.EXPENSE, active = false),
    )

    @Test
    fun `canteen order with inferred merchant fills every field`() {
        val result = parse(
            """
            {"kind":"EXPENSE","amount":"16.65","direction":"OUT","transactionTime":"2026-10-11 11:29",
             "counterparty":"吉林大学食堂","counterpartyInferred":true,"counterpartyBasis":"网址 jdzhst.jlu.edu.cn",
             "channel":"OTHER","platform":"吉林大学智慧食堂","paymentMethod":"电子账户","itemsSummary":"食堂 6 件菜品",
             "categoryId":1,"warnings":[]}
            """,
        )

        val transaction = recognized(result)
        assertEquals(AccountingType.EXPENSE, transaction.accountingType)
        assertEquals(TransactionDirection.OUT, transaction.direction)
        assertEquals(1665L, transaction.amountCents)
        assertEquals(time(2026, 10, 11, 11, 29), transaction.transactionTimeMillis)
        assertEquals("吉林大学食堂", transaction.counterparty)
        assertTrue(transaction.counterpartyInferred)
        assertEquals("网址 jdzhst.jlu.edu.cn", transaction.counterpartyBasis)
        assertEquals(PaymentChannel.OTHER, transaction.paymentChannel)
        assertEquals("吉林大学智慧食堂 · 电子账户 · 食堂 6 件菜品", transaction.note)
        assertEquals(1L, transaction.categoryId)
        assertTrue(transaction.warnings.isEmpty())
    }

    @Test
    fun `fenced json with numeric amount and currency noise is accepted`() {
        val result = parse(
            "```json\n{\"kind\":\"EXPENSE\",\"amount\":\"-¥1,234.50\",\"channel\":\"ALIPAY\"," +
                "\"platform\":\"支付宝\",\"counterparty\":\"某某咖啡\",\"counterpartyInferred\":false}\n```",
        )

        val transaction = recognized(result)
        assertEquals(123_450L, transaction.amountCents)
        assertEquals(PaymentChannel.OTHER, transaction.paymentChannel)
        assertEquals("支付宝", transaction.note)
        assertFalse(transaction.counterpartyInferred)
        assertNull(transaction.counterpartyBasis)
    }

    @Test
    fun `wechat platform is not repeated in note`() {
        val result = parse(
            """{"kind":"EXPENSE","amount":12.5,"channel":"WECHAT","platform":"微信","paymentMethod":"零钱"}""",
        )

        val transaction = recognized(result)
        assertEquals(1250L, transaction.amountCents)
        assertEquals(PaymentChannel.WECHAT, transaction.paymentChannel)
        assertEquals("零钱", transaction.note)
    }

    @Test
    fun `missing amount and time are left empty for the form`() {
        val result = parse("""{"kind":"EXPENSE","amount":null,"transactionTime":null,"counterparty":null}""")

        val transaction = recognized(result)
        assertNull(transaction.amountCents)
        assertNull(transaction.transactionTimeMillis)
        assertNull(transaction.counterparty)
        assertEquals("", transaction.note)
    }

    @Test
    fun `future or very old time is rejected with a warning`() {
        val future = recognized(parse("""{"kind":"EXPENSE","amount":"5","transactionTime":"2026-10-23 09:00"}"""))
        val old = recognized(parse("""{"kind":"EXPENSE","amount":"5","transactionTime":"2024-01-01 09:00"}"""))

        assertNull(future.transactionTimeMillis)
        assertTrue(future.warnings.single().contains("不合理"))
        assertNull(old.transactionTimeMillis)
    }

    @Test
    fun `date without clock time uses noon and says so`() {
        val transaction = recognized(parse("""{"kind":"EXPENSE","amount":"5","transactionTime":"2026年10月11日"}"""))

        assertEquals(time(2026, 10, 11, 12, 0), transaction.transactionTimeMillis)
        assertTrue(transaction.warnings.single().contains("12:00"))
    }

    @Test
    fun `iso style time is parsed`() {
        val transaction = recognized(parse("""{"kind":"EXPENSE","amount":"5","transactionTime":"2026-10-11T08:05:59"}"""))

        assertEquals(time(2026, 10, 11, 8, 5, 59), transaction.transactionTimeMillis)
    }

    @Test
    fun `income and refund follow the editor direction rules`() {
        val income = recognized(parse("""{"kind":"INCOME","amount":"100","direction":"OUT","categoryId":2}"""))
        val refund = recognized(parse("""{"kind":"REFUND","amount":"8","direction":"IN","categoryId":2}"""))
        val transfer = recognized(parse("""{"kind":"TRANSFER","amount":"200","direction":"IN"}"""))

        assertEquals(TransactionDirection.IN, income.direction)
        assertEquals(2L, income.categoryId)
        assertEquals(AccountingType.REFUND, refund.accountingType)
        assertEquals(TransactionDirection.OUT, refund.direction)
        // 工资是收入分类，不能用于退款。
        assertNull(refund.categoryId)
        assertEquals(TransactionDirection.IN, transfer.direction)
    }

    @Test
    fun `inactive or unknown category is ignored`() {
        val inactive = recognized(parse("""{"kind":"EXPENSE","amount":"5","categoryId":3}"""))
        val unknown = recognized(parse("""{"kind":"EXPENSE","amount":"5","categoryId":"99"}"""))
        val zero = recognized(parse("""{"kind":"EXPENSE","amount":"5","categoryId":0}"""))

        assertNull(inactive.categoryId)
        assertNull(unknown.categoryId)
        assertNull(zero.categoryId)
    }

    @Test
    fun `not a transaction and multiple transactions are reported`() {
        val notTransaction = parse("""{"kind":"NOT_TRANSACTION"}""")
        val multiple = parse("""{"kind":"MULTIPLE"}""")

        assertTrue(notTransaction is ScreenshotRecognitionResult.Failed)
        assertTrue((multiple as ScreenshotRecognitionResult.Failed).reason.contains("多笔"))
    }

    @Test
    fun `unknown kind without amount is a failure but with amount defaults to expense`() {
        assertTrue(parse("""{"kind":"SOMETHING"}""") is ScreenshotRecognitionResult.Failed)

        val transaction = recognized(parse("""{"kind":"SOMETHING","amount":"3.5"}"""))
        assertEquals(AccountingType.EXPENSE, transaction.accountingType)
        assertEquals(350L, transaction.amountCents)
    }

    @Test
    fun `model warnings are trimmed and limited`() {
        val transaction = recognized(
            parse("""{"kind":"EXPENSE","amount":"5","warnings":["一"," 二 ","三","四"]}"""),
        )

        assertEquals(listOf("一", "二", "三"), transaction.warnings)
    }

    @Test
    fun `empty or malformed content fails`() {
        val empty = ScreenshotResponseParser.parseResponse(
            """{"choices":[{"message":{"content":""}}]}""",
            categories,
            now,
            zone,
        )
        val malformed = parse("这不是 JSON")
        val notJson = ScreenshotResponseParser.parseResponse("<html>", categories, now, zone)

        assertTrue(empty is ScreenshotRecognitionResult.Failed)
        assertTrue(malformed is ScreenshotRecognitionResult.Failed)
        assertTrue(notJson is ScreenshotRecognitionResult.Failed)
    }

    @Test
    fun `amount parsing accepts common formats and rejects invalid ones`() {
        assertEquals(1665L, ScreenshotResponseParser.parseAmountCents("16.65"))
        assertEquals(1660L, ScreenshotResponseParser.parseAmountCents("16.6元"))
        assertEquals(1600L, ScreenshotResponseParser.parseAmountCents("￥16.000"))
        assertEquals(1665L, ScreenshotResponseParser.parseAmountCents("+16.65"))
        assertNull(ScreenshotResponseParser.parseAmountCents("16.655"))
        assertNull(ScreenshotResponseParser.parseAmountCents("0"))
        assertNull(ScreenshotResponseParser.parseAmountCents("abc"))
        assertNull(ScreenshotResponseParser.parseAmountCents(null))
    }

    private fun parse(content: String): ScreenshotRecognitionResult {
        val response = buildJsonObject {
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "message",
                                buildJsonObject {
                                    put("content", content.trimIndent())
                                },
                            )
                        },
                    )
                },
            )
        }.toString()
        return ScreenshotResponseParser.parseResponse(response, categories, now, zone)
    }

    private fun recognized(result: ScreenshotRecognitionResult): RecognizedTransaction {
        assertTrue("期望识别成功，实际：$result", result is ScreenshotRecognitionResult.Recognized)
        return (result as ScreenshotRecognitionResult.Recognized).transaction
    }

    private fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long {
        return LocalDateTime.of(year, month, day, hour, minute, second).atZone(zone).toInstant().toEpochMilli()
    }

    private fun category(id: Long, name: String, type: AccountingType, active: Boolean = true): CategoryEntity {
        return CategoryEntity(
            id = id,
            name = name,
            accountingType = type,
            sortOrder = id.toInt(),
            isActive = active,
            isSystem = false,
        )
    }
}
