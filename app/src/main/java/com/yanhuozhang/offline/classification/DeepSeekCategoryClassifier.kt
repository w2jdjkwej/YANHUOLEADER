package com.yanhuozhang.offline.classification

import com.yanhuozhang.offline.data.RemoteClassificationContext
import com.yanhuozhang.offline.settings.DeepSeekModel
import com.yanhuozhang.offline.settings.DeepSeekThinkingMode
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

sealed interface RemoteCategoryResult {
    data class Classified(
        val categoryId: Long,
        val confidence: Double,
        val reason: String,
    ) : RemoteCategoryResult

    data class NeedsManualReview(val reason: String) : RemoteCategoryResult
}

class DeepSeekCategoryClassifier {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun classify(
        context: RemoteClassificationContext,
        apiKey: String,
        model: DeepSeekModel = DeepSeekModel.FLASH,
        thinkingMode: DeepSeekThinkingMode = DeepSeekThinkingMode.DISABLED,
    ): RemoteCategoryResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext RemoteCategoryResult.NeedsManualReview("未配置 DeepSeek API Key")
        }
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = if (thinkingMode == DeepSeekThinkingMode.ENABLED) {
                THINKING_READ_TIMEOUT_MILLIS
            } else {
                STANDARD_READ_TIMEOUT_MILLIS
            }
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("Content-Type", "application/json")
        }

        try {
            val requestBody = buildRequest(context, model, thinkingMode)
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
            }
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                return@withContext RemoteCategoryResult.NeedsManualReview(
                    "DeepSeek 请求失败（HTTP $statusCode）",
                )
            }
            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText()
            }
            parseResponse(responseText, context)
        } catch (error: Exception) {
            RemoteCategoryResult.NeedsManualReview(
                error.message?.takeIf { message -> message.isNotBlank() }
                    ?: "DeepSeek 请求异常",
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun buildRequest(
        context: RemoteClassificationContext,
        model: DeepSeekModel = DeepSeekModel.FLASH,
        thinkingMode: DeepSeekThinkingMode = DeepSeekThinkingMode.DISABLED,
    ): String {
        val record = context.record
        val categories = buildJsonArray {
            for (category in context.categories) {
                add(
                    buildJsonObject {
                        put("id", category.id)
                        put("name", category.name)
                    },
                )
            }
        }
        val transaction = buildJsonObject {
            put("counterparty", record.counterparty.orEmpty())
            put("note", record.note)
            put("paymentChannel", record.paymentChannel.name)
            put("direction", record.direction.name)
            put("amountCents", record.amountCents)
            put("candidateCategories", categories)
        }
        val systemPrompt = """
            你是个人记账应用的支出分类器。只能从 candidateCategories 中选择分类。
            信息不足或存在明显歧义时，categoryId 必须返回 0，并将 needsManualReview 设为 true。
            不得创建新分类。请只输出 JSON：
            {"categoryId":整数,"confidence":0到1的小数,"reason":"简短理由","needsManualReview":布尔值}
        """.trimIndent()
        return buildJsonObject {
            put("model", model.apiValue)
            put(
                "thinking",
                buildJsonObject {
                    put("type", thinkingMode.apiValue)
                },
            )
            put("temperature", 0.1)
            put(
                "max_tokens",
                if (thinkingMode == DeepSeekThinkingMode.ENABLED) {
                    THINKING_MAX_TOKENS
                } else {
                    STANDARD_MAX_TOKENS
                },
            )
            put(
                "response_format",
                buildJsonObject {
                    put("type", "json_object")
                },
            )
            put(
                "messages",
                buildJsonArray {
                    add(message("system", systemPrompt))
                    add(message("user", transaction.toString()))
                },
            )
        }.toString()
    }

    internal fun parseResponse(
        responseText: String,
        context: RemoteClassificationContext,
    ): RemoteCategoryResult {
        return try {
            val response = json.parseToJsonElement(responseText).jsonObject
            val choices = response["choices"]?.jsonArray ?: JsonArray(emptyList())
            val content = choices.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.content
                ?: return RemoteCategoryResult.NeedsManualReview("DeepSeek 未返回分类结果")
            val result = json.parseToJsonElement(content).jsonObject
            val categoryId = result["categoryId"]?.jsonPrimitive?.longOrNull ?: 0L
            val confidence = result["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val reason = result["reason"]?.jsonPrimitive?.content.orEmpty()
            val needsManualReview =
                result["needsManualReview"]?.jsonPrimitive?.booleanOrNull ?: true
            val isAllowedCategory = context.categories.any { category -> category.id == categoryId }
            if (needsManualReview || !isAllowedCategory || confidence < MINIMUM_CONFIDENCE) {
                RemoteCategoryResult.NeedsManualReview(
                    reason.ifBlank { "DeepSeek 无法可靠判断分类" },
                )
            } else {
                RemoteCategoryResult.Classified(categoryId, confidence, reason)
            }
        } catch (_: Exception) {
            RemoteCategoryResult.NeedsManualReview("DeepSeek 返回内容无法解析")
        }
    }

    private fun message(role: String, content: String): JsonObject {
        return buildJsonObject {
            put("role", role)
            put("content", content)
        }
    }

    companion object {
        private const val API_URL = "https://api.deepseek.com/chat/completions"
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val STANDARD_READ_TIMEOUT_MILLIS = 15_000
        private const val THINKING_READ_TIMEOUT_MILLIS = 45_000
        private const val STANDARD_MAX_TOKENS = 160
        private const val THINKING_MAX_TOKENS = 1_024
        private const val MINIMUM_CONFIDENCE = 0.75
    }
}
