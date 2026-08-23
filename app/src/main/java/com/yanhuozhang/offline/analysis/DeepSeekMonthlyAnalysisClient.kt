package com.yanhuozhang.offline.analysis

import com.yanhuozhang.offline.settings.DeepSeekModel
import com.yanhuozhang.offline.settings.DeepSeekThinkingMode
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class MonthlyCategorySnapshot(
    val name: String,
    val expenseCents: Long,
    val sharePercent: Double,
)

data class MonthlyExpenseSnapshot(
    val monthKey: String,
    val totalExpenseCents: Long,
    val categories: List<MonthlyCategorySnapshot>,
    val fingerprint: String,
)

data class MonthlyAnalysisContent(
    val summary: String,
    val observations: List<String>,
    val suggestion: String?,
    val lightComment: String?,
)

sealed interface MonthlyAnalysisApiResult {
    data class Success(val content: MonthlyAnalysisContent) : MonthlyAnalysisApiResult
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
    private val json = Json { ignoreUnknownKeys = true }

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
            parseResponse(responseText)
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
        val categories = buildJsonArray {
            for (category in snapshot.categories) {
                add(
                    buildJsonObject {
                        put("name", category.name)
                        put("expense_cents", category.expenseCents)
                        put("share_percent", category.sharePercent)
                    },
                )
            }
        }
        val monthlyData = buildJsonObject {
            put("month", snapshot.monthKey)
            put("total_expense_cents", snapshot.totalExpenseCents)
            put("categories", categories)
        }
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
                    add(message("system", SYSTEM_PROMPT))
                    add(message("user", monthlyData.toString()))
                },
            )
        }.toString()
    }

    internal fun parseResponse(responseText: String): MonthlyAnalysisApiResult {
        return try {
            val response = json.parseToJsonElement(responseText).jsonObject
            val choices = response["choices"]?.jsonArray ?: JsonArray(emptyList())
            val contentText = choices.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.content
                ?: return MonthlyAnalysisApiResult.Failure("DeepSeek 未返回月度分析")
            val content = json.parseToJsonElement(contentText).jsonObject
            val summary = sanitizeRequired(content["summary"]?.jsonPrimitive?.contentOrNull)
                ?: return MonthlyAnalysisApiResult.Failure("月度分析摘要为空")
            val observations = content["observations"]?.jsonArray
                ?.mapNotNull { element -> sanitizeOptional(element.jsonPrimitive.contentOrNull) }
                ?.distinct()
                ?.take(MAX_OBSERVATIONS)
                .orEmpty()
            val suggestion = nullableText(content["suggestion"])
            val lightComment = sanitizeLightComment(nullableText(content["light_comment"]))
            MonthlyAnalysisApiResult.Success(
                MonthlyAnalysisContent(
                    summary = summary,
                    observations = observations,
                    suggestion = suggestion,
                    lightComment = lightComment,
                ),
            )
        } catch (_: Exception) {
            MonthlyAnalysisApiResult.Failure("DeepSeek 月度分析返回内容无法解析")
        }
    }

    private fun nullableText(element: kotlinx.serialization.json.JsonElement?): String? {
        if (element == null || element is JsonNull) {
            return null
        }
        return sanitizeOptional(element.jsonPrimitive.contentOrNull)
    }

    private fun sanitizeRequired(text: String?): String? {
        return sanitizeOptional(text)
    }

    private fun sanitizeOptional(text: String?): String? {
        val normalized = text
            ?.replace('！', '。')
            ?.replace('!', '。')
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.take(MAX_TEXT_LENGTH)
            .orEmpty()
        return normalized.ifBlank { null }
    }

    private fun sanitizeLightComment(text: String?): String? {
        val comment = text?.take(MAX_LIGHT_COMMENT_LENGTH) ?: return null
        val forbiddenFragments = listOf("哈哈", "吃土", "月光族", "离谱", "穷", "剁手")
        if (forbiddenFragments.any { fragment -> comment.contains(fragment) }) {
            return null
        }
        return comment
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    companion object {
        private const val API_URL = "https://api.deepseek.com/chat/completions"
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val STANDARD_READ_TIMEOUT_MILLIS = 20_000
        private const val THINKING_READ_TIMEOUT_MILLIS = 60_000
        private const val STANDARD_MAX_TOKENS = 600
        private const val THINKING_MAX_TOKENS = 1_600
        private const val MAX_OBSERVATIONS = 3
        private const val MAX_TEXT_LENGTH = 160
        private const val MAX_LIGHT_COMMENT_LENGTH = 80

        private val SYSTEM_PROMPT = """
            你是个人记账应用的月度分类支出分析助手。只能依据用户提供的月度聚合数据分析，
            不得虚构消费原因、收入、预算、债务、家庭状况或用户性格。

            输出必须是以下 JSON，不得增加字段：
            {
              "summary": "一到两句总体概述",
              "observations": ["最多三条、每条一句的客观发现"],
              "suggestion": "一条克制、可执行且非强制的建议，无法提出时为 null",
              "light_comment": "最多一句轻度幽默，无法自然表达时为 null"
            }

            语言要求：
            1. 整体语气客观、平静、简洁。
            2. 可以带有轻度幽默，但幽默只能出现在 light_comment 字段中。
            3. 每次报告最多生成一句 light_comment。
            4. 幽默优先使用轻微比喻、克制的拟人或生活化观察。
            5. 禁止谐音梗、网络热梗、土味表达、鸡汤和感叹号。
            6. 禁止嘲讽、批评或评价用户的人格、自制力和消费能力。
            7. 不得拿医疗、家庭、债务、收入下降等敏感支出开玩笑。
            8. 若没有合适且自然的幽默表达，light_comment 必须返回 null。

            合适的 light_comment 示例：
            “预算还没离家出走，不过已经站在门口穿鞋了。”
            “购物支出这月明显抢了几次镜头。”
            “餐饮依旧稳坐支出榜首席。”

            禁止的表达示例：
            “钱包已经被你掏空啦哈哈哈！”
            “再买就要吃土咯！”
            “恭喜你成功成为月光族！”
            “这个月花钱如流水，简直离谱！”
        """.trimIndent()
    }
}
