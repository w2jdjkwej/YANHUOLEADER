package com.yanhuozhang.offline.recognition

import com.yanhuozhang.offline.data.CategoryEntity
import com.yanhuozhang.offline.settings.DeepSeekModel
import com.yanhuozhang.offline.settings.DeepSeekThinkingMode
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun interface ScreenshotRecognizer {
    suspend fun recognize(
        image: EncodedScreenshot,
        categories: List<CategoryEntity>,
        apiKey: String,
        nowMillis: Long,
        zoneId: ZoneId,
    ): ScreenshotRecognitionResult
}

/**
 * 把截图直接发给 DeepSeek 视觉模型识别。
 *
 * 不跟随设置里的模型和思考选项：只有 Flash 支持图片输入，思考模式固定开启；
 * 也不设 max_tokens，思考过程和回答都不受长度限制。
 */
class DeepSeekScreenshotRecognizer : ScreenshotRecognizer {
    private val json = Json { ignoreUnknownKeys = true }

    private class HttpResponse(val statusCode: Int, val body: String)

    override suspend fun recognize(
        image: EncodedScreenshot,
        categories: List<CategoryEntity>,
        apiKey: String,
        nowMillis: Long,
        zoneId: ZoneId,
    ): ScreenshotRecognitionResult {
        if (apiKey.isBlank()) {
            return ScreenshotRecognitionResult.Failed("请先在设置中启用 DeepSeek 并填写 API Key")
        }
        return try {
            var response = post(apiKey, buildRequest(image, categories, nowMillis, zoneId, jsonMode = true))
            if (response.statusCode == 400) {
                // 文档没写明“思考模式 + 图片 + JSON Output”能否同时使用；被拒绝时去掉 JSON Output 再试一次，
                // 解析时会从回答里提取 JSON。
                response = post(apiKey, buildRequest(image, categories, nowMillis, zoneId, jsonMode = false))
            }
            if (response.statusCode !in 200..299) {
                ScreenshotRecognitionResult.Failed(failureReason(response))
            } else {
                ScreenshotResponseParser.parseResponse(response.body, categories, nowMillis, zoneId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 取消时会主动断开连接，阻塞的读写随之抛出 IOException；这种情况按取消处理。
            currentCoroutineContext().ensureActive()
            ScreenshotRecognitionResult.Failed(
                when (error) {
                    is SocketTimeoutException -> "DeepSeek 识别超时，请稍后重试"
                    is UnknownHostException -> "网络不可用，无法连接 DeepSeek"
                    else -> error.message?.takeIf { message -> message.isNotBlank() }
                        ?: "DeepSeek 截图识别请求异常"
                },
            )
        }
    }

    internal fun buildRequest(
        image: EncodedScreenshot,
        categories: List<CategoryEntity>,
        nowMillis: Long,
        zoneId: ZoneId,
        jsonMode: Boolean,
    ): String {
        val userText = ScreenshotRecognitionPrompt.buildUserText(categories, nowMillis, zoneId)
        return buildJsonObject {
            put("model", DeepSeekModel.FLASH.apiValue)
            put(
                "thinking",
                buildJsonObject {
                    put("type", DeepSeekThinkingMode.ENABLED.apiValue)
                },
            )
            if (jsonMode) {
                put(
                    "response_format",
                    buildJsonObject {
                        put("type", "json_object")
                    },
                )
            }
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", ScreenshotRecognitionPrompt.systemPrompt)
                        },
                    )
                    // 图片只能放在 user 消息里。
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", userText)
                                        },
                                    )
                                    add(
                                        buildJsonObject {
                                            put("type", "image_url")
                                            put(
                                                "image_url",
                                                buildJsonObject {
                                                    put("url", image.toDataUrl())
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }.toString()
    }

    private suspend fun post(apiKey: String, body: String): HttpResponse = coroutineScope {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("Content-Type", "application/json")
        }
        // 阻塞的 socket 读写不响应协程取消：用户点“取消”时主动断开连接，让它立刻结束。
        val disconnectOnCancel = launch(Dispatchers.IO) {
            try {
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { output -> output.write(bytes) }
                val statusCode = connection.responseCode
                val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader -> reader.readText() }.orEmpty()
                HttpResponse(statusCode, text)
            }
        } finally {
            disconnectOnCancel.cancel()
        }
    }

    private fun failureReason(response: HttpResponse): String {
        val apiMessage = try {
            json.parseToJsonElement(response.body).jsonObject["error"]
                ?.jsonObject
                ?.get("message")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.take(MAX_API_MESSAGE_LENGTH)
        } catch (_: Exception) {
            null
        }
        val code = response.statusCode
        return when (code) {
            401 -> "DeepSeek API Key 无效，请在设置中检查"
            402 -> "DeepSeek 账户余额不足"
            429 -> "DeepSeek 请求过于频繁，请稍后重试"
            in 500..599 -> "DeepSeek 服务暂时不可用（HTTP $code），请稍后重试"
            else -> "DeepSeek 截图识别失败（HTTP $code）" + apiMessage?.let { message -> "：$message" }.orEmpty()
        }
    }

    companion object {
        private const val API_URL = "https://api.deepseek.com/chat/completions"
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 180_000
        private const val MAX_API_MESSAGE_LENGTH = 120
    }
}
