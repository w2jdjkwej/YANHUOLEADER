package com.yanhuozhang.offline.analysis

import com.yanhuozhang.offline.settings.DeepSeekModel
import com.yanhuozhang.offline.settings.DeepSeekThinkingMode
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed interface MonthlyAnalysisApiResult {
    data class Success(val content: MonthlyAiAnalysis) : MonthlyAnalysisApiResult
    data class Failure(val reason: String) : MonthlyAnalysisApiResult
}

fun interface MonthlyAnalysisClient {
    suspend fun analyze(
        snapshot: MonthlyExpenseSnapshot,
        apiKey: String,
        model: DeepSeekModel,
        thinkingMode: DeepSeekThinkingMode,
    ): MonthlyAnalysisApiResult
}

class DeepSeekMonthlyAnalysisClient : MonthlyAnalysisClient {
    override suspend fun analyze(
        snapshot: MonthlyExpenseSnapshot,
        apiKey: String,
        model: DeepSeekModel,
        thinkingMode: DeepSeekThinkingMode,
    ): MonthlyAnalysisApiResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext MonthlyAnalysisApiResult.Failure("未配置 DeepSeek API Key")
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
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(buildRequest(snapshot, model, thinkingMode))
            }
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                return@withContext MonthlyAnalysisApiResult.Failure(
                    "DeepSeek 月度分析请求失败（HTTP $statusCode）",
                )
            }
            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText()
            }
            parseResponse(responseText, snapshot.payload.summary.grossExpenseCents)
        } catch (error: Exception) {
            MonthlyAnalysisApiResult.Failure(
                error.message?.takeIf { message -> message.isNotBlank() }
                    ?: "DeepSeek 月度分析请求异常",
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun buildRequest(
        snapshot: MonthlyExpenseSnapshot,
        model: DeepSeekModel,
        thinkingMode: DeepSeekThinkingMode,
    ): String {
        return buildJsonObject {
            put("model", model.apiValue)
            put(
                "thinking",
                buildJsonObject {
                    put("type", thinkingMode.apiValue)
                },
            )
            put("temperature", 0.2)
            put(
                "response_format",
                buildJsonObject {
                    put("type", "json_object")
                },
            )
            put(
                "messages",
                buildJsonArray {
                    add(message("system", MonthlyAnalysisPromptBuilder.systemPrompt))
                    add(message("user", MonthlyAnalysisPromptBuilder.buildUserContent(snapshot.payload)))
                },
            )
        }.toString()
    }

    internal fun parseResponse(
        responseText: String,
        grossExpenseCents: Long = Long.MAX_VALUE,
    ): MonthlyAnalysisApiResult {
        return try {
            val response = MonthlyAnalysisJson.format.parseToJsonElement(responseText).jsonObject
            val choices = response["choices"]?.jsonArray ?: JsonArray(emptyList())
            val choice = choices.firstOrNull()?.jsonObject
                ?: return MonthlyAnalysisApiResult.Failure("DeepSeek 未返回月度分析")
            if (choice["finish_reason"]?.jsonPrimitive?.content == "length") {
                return MonthlyAnalysisApiResult.Failure(
                    "DeepSeek 月度分析达到输出上限，内容被截断，请重试或关闭思考模式",
                )
            }
            val contentText = choice
                .get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.content
                ?: return MonthlyAnalysisApiResult.Failure("DeepSeek 未返回月度分析")
            if (contentText.isBlank()) {
                return MonthlyAnalysisApiResult.Failure("DeepSeek 月度分析正文为空，请重试")
            }
            val jsonText = extractJsonObject(contentText)
                ?: return MonthlyAnalysisApiResult.Failure("DeepSeek 月度分析返回内容无法解析")
            val decoded = MonthlyAnalysisJson.format.decodeFromString(
                MonthlyAiAnalysis.serializer(),
                jsonText,
            )
            sanitize(decoded, grossExpenseCents)
        } catch (_: SerializationException) {
            MonthlyAnalysisApiResult.Failure("DeepSeek 月度分析返回内容无法解析")
        } catch (_: IllegalArgumentException) {
            MonthlyAnalysisApiResult.Failure("DeepSeek 月度分析返回内容无法解析")
        } catch (_: Exception) {
            MonthlyAnalysisApiResult.Failure("DeepSeek 月度分析返回内容无法解析")
        }
    }

    private fun sanitize(
        decoded: MonthlyAiAnalysis,
        grossExpenseCents: Long,
    ): MonthlyAnalysisApiResult {
        val findings = decoded.keyFindings
            .map { item ->
                AiKeyFinding(
                    title = sanitizeText(item.title, MAX_TITLE_LENGTH).orEmpty(),
                    description = sanitizeText(item.description, MAX_DESCRIPTION_LENGTH).orEmpty(),
                    evidence = sanitizeText(item.evidence, MAX_EVIDENCE_LENGTH).orEmpty(),
                    importance = item.importance.lowercase().takeIf { value ->
                        value == "high" || value == "medium" || value == "low"
                    } ?: "medium",
                )
            }
            .filter { item -> item.title.isNotBlank() || item.description.isNotBlank() }
            .distinct()
            .take(MAX_KEY_FINDINGS)
        val headline = sanitizeText(decoded.headline, MAX_TITLE_LENGTH)
        val overallAssessment = sanitizeText(decoded.overallAssessment, MAX_DESCRIPTION_LENGTH)
        if (headline == null && overallAssessment == null && findings.isEmpty()) {
            return MonthlyAnalysisApiResult.Failure("月度分析核心内容为空")
        }

        val potentialSavings = decoded.potentialSavings?.let { savings ->
            val explanation = sanitizeText(savings.explanation, MAX_DESCRIPTION_LENGTH)
            if (savings.minCents < 0L ||
                savings.maxCents < savings.minCents ||
                savings.maxCents > grossExpenseCents ||
                explanation == null
            ) {
                return MonthlyAnalysisApiResult.Failure("DeepSeek 返回的可优化金额无效")
            }
            AiPotentialSavings(
                minCents = savings.minCents,
                maxCents = savings.maxCents,
                explanation = explanation,
            )
        }

        val sanitized = MonthlyAiAnalysis(
            headline = headline ?: "本月消费分析",
            overallAssessment = overallAssessment.orEmpty(),
            keyFindings = findings,
            categoryInsights = decoded.categoryInsights
                .map { item ->
                    AiCategoryInsight(
                        category = sanitizeText(item.category, MAX_TITLE_LENGTH).orEmpty(),
                        insight = sanitizeText(item.insight, MAX_DESCRIPTION_LENGTH).orEmpty(),
                    )
                }
                .filter { item -> item.category.isNotBlank() && item.insight.isNotBlank() }
                .distinct()
                .take(MAX_CATEGORY_INSIGHTS),
            behaviorInsights = sanitizeInsights(decoded.behaviorInsights),
            abnormalExpenses = sanitizeInsights(decoded.abnormalExpenses),
            recurringPayments = sanitizeInsights(decoded.recurringPayments),
            trendAnalysis = sanitizeText(decoded.trendAnalysis, MAX_DESCRIPTION_LENGTH),
            dailyExpenseAssessment = sanitizeText(
                decoded.dailyExpenseAssessment,
                MAX_DESCRIPTION_LENGTH,
            ),
            potentialSavings = potentialSavings,
            nextMonthSuggestions = sanitizeTextList(
                decoded.nextMonthSuggestions,
                MAX_SUGGESTIONS,
            ),
            positiveObservations = sanitizeTextList(
                decoded.positiveObservations,
                MAX_LIST_ITEMS,
            ),
            dataLimitations = sanitizeTextList(decoded.dataLimitations, MAX_LIST_ITEMS),
        )
        return MonthlyAnalysisApiResult.Success(sanitized)
    }

    private fun sanitizeInsights(items: List<AiInsight>): List<AiInsight> {
        return items.map { item ->
            AiInsight(
                title = sanitizeText(item.title, MAX_TITLE_LENGTH).orEmpty(),
                description = sanitizeText(item.description, MAX_DESCRIPTION_LENGTH).orEmpty(),
                evidence = sanitizeText(item.evidence, MAX_EVIDENCE_LENGTH).orEmpty(),
            )
        }.filter { item -> item.title.isNotBlank() || item.description.isNotBlank() }
            .distinct()
            .take(MAX_LIST_ITEMS)
    }

    private fun sanitizeTextList(items: List<String>, limit: Int): List<String> {
        return items.mapNotNull { item -> sanitizeText(item, MAX_DESCRIPTION_LENGTH) }
            .distinct()
            .take(limit)
    }

    private fun sanitizeText(text: String?, maxLength: Int): String? {
        val normalized = text
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.take(maxLength)
            .orEmpty()
        return normalized.ifBlank { null }
    }

    private fun extractJsonObject(contentText: String): String? {
        var normalized = contentText.trim()
        if (normalized.startsWith("```")) {
            normalized = normalized.substringAfter('\n', "")
            normalized = normalized.substringBeforeLast("```").trim()
        }
        val firstBrace = normalized.indexOf('{')
        val lastBrace = normalized.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            return null
        }
        return normalized.substring(firstBrace, lastBrace + 1)
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    companion object {
        private const val API_URL = "https://api.deepseek.com/chat/completions"
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val STANDARD_READ_TIMEOUT_MILLIS = 60_000
        private const val THINKING_READ_TIMEOUT_MILLIS = 180_000
        private const val MAX_KEY_FINDINGS = 3
        private const val MAX_SUGGESTIONS = 3
        private const val MAX_CATEGORY_INSIGHTS = 20
        private const val MAX_LIST_ITEMS = 20
        private const val MAX_TITLE_LENGTH = 120
        private const val MAX_DESCRIPTION_LENGTH = 600
        private const val MAX_EVIDENCE_LENGTH = 300
    }
}
