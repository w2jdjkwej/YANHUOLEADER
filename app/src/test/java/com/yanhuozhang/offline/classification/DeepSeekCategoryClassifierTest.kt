package com.yanhuozhang.offline.classification

import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.StatisticsEffect
import com.yanhuozhang.offline.core.TransactionDirection
import com.yanhuozhang.offline.data.CategoryEntity
import com.yanhuozhang.offline.data.LedgerRecordEntity
import com.yanhuozhang.offline.data.LedgerStatus
import com.yanhuozhang.offline.data.RemoteClassificationContext
import com.yanhuozhang.offline.settings.DeepSeekModel
import com.yanhuozhang.offline.settings.DeepSeekThinkingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekCategoryClassifierTest {
    private val classifier = DeepSeekCategoryClassifier()
    private val context = RemoteClassificationContext(
        record = LedgerRecordEntity(
            id = 1,
            amountCents = 2350,
            direction = TransactionDirection.OUT,
            accountingType = AccountingType.EXPENSE,
            statisticsEffect = StatisticsEffect.ADD_EXPENSE,
            categoryId = 1,
            paymentChannel = PaymentChannel.WECHAT,
            counterparty = "示例餐饮服务有限公司",
            transactionTimeMillis = 1_000,
            relatedRecordId = null,
            note = "",
            status = LedgerStatus.ACTIVE,
            createdAtMillis = 1_000,
            updatedAtMillis = 1_000,
        ),
        categories = listOf(
            category(2, "餐饮"),
            category(3, "交通"),
        ),
    )

    @Test
    fun `valid allowed category is accepted`() {
        val response = """
            {
              "choices": [
                {
                  "message": {
                    "content": "{\"categoryId\":2,\"confidence\":0.93,\"reason\":\"餐饮商户\",\"needsManualReview\":false}"
                  }
                }
              ]
            }
        """.trimIndent()

        val result = classifier.parseResponse(response, context)

        assertTrue(result is RemoteCategoryResult.Classified)
        assertEquals(2L, (result as RemoteCategoryResult.Classified).categoryId)
    }

    @Test
    fun `unknown category id is rejected`() {
        val response = """
            {
              "choices": [
                {
                  "message": {
                    "content": "{\"categoryId\":99,\"confidence\":0.99,\"reason\":\"未知分类\",\"needsManualReview\":false}"
                  }
                }
              ]
            }
        """.trimIndent()

        assertTrue(
            classifier.parseResponse(response, context) is RemoteCategoryResult.NeedsManualReview,
        )
    }

    @Test
    fun `default request uses flash model without thinking`() {
        val request = classifier.buildRequest(context)

        assertTrue(request.contains("\"model\":\"deepseek-v4-flash\""))
        assertTrue(request.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(request.contains("\"max_tokens\":160"))
        assertTrue(request.contains("\"type\":\"json_object\""))
        assertTrue(request.contains("\\\"candidateCategories\\\""))
    }

    @Test
    fun `selected pro model and thinking mode are sent`() {
        val request = classifier.buildRequest(
            context = context,
            model = DeepSeekModel.PRO,
            thinkingMode = DeepSeekThinkingMode.ENABLED,
        )

        assertTrue(request.contains("\"model\":\"deepseek-v4-pro\""))
        assertTrue(request.contains("\"thinking\":{\"type\":\"enabled\"}"))
        assertTrue(request.contains("\"max_tokens\":1024"))
    }

    private fun category(id: Long, name: String): CategoryEntity {
        return CategoryEntity(
            id = id,
            name = name,
            accountingType = AccountingType.EXPENSE,
            sortOrder = id.toInt(),
            isActive = true,
            isSystem = true,
        )
    }
}
