package com.yanhuozhang.offline.recognition

import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.data.CategoryEntity
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekScreenshotRecognizerTest {
    private val recognizer = DeepSeekScreenshotRecognizer()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 22, 14, 30).atZone(zone).toInstant().toEpochMilli()
    private val image = EncodedScreenshot("image/jpeg", byteArrayOf(1, 2, 3))
    private val categories = listOf(
        CategoryEntity(
            id = 7,
            name = "餐饮",
            accountingType = AccountingType.EXPENSE,
            sortOrder = 1,
            isActive = true,
            isSystem = true,
        ),
    )

    @Test
    fun `request uses flash with thinking and json output and sends the image in the user message`() {
        val request = Json.parseToJsonElement(
            recognizer.buildRequest(image, categories, now, zone, jsonMode = true),
        ).jsonObject

        assertEquals("deepseek-flash", request.getValue("model").jsonPrimitive.content)
        assertEquals(
            "enabled",
            request.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            "json_object",
            request.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content,
        )
        // 不设输出上限；思考模式下 temperature 不生效，也不传。
        assertFalse("max_tokens" in request)
        assertFalse("temperature" in request)

        val messages = request.getValue("messages").jsonArray
        assertEquals("system", messages[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertTrue(messages[0].jsonObject.getValue("content").jsonPrimitive.content.contains("JSON"))
        val userParts = messages[1].jsonObject.getValue("content").jsonArray
        val text = userParts[0].jsonObject.getValue("text").jsonPrimitive.content
        assertTrue(text.contains("2026-10-22 14:30"))
        assertTrue(text.contains("Asia/Shanghai"))
        assertTrue(text.contains("餐饮"))
        assertEquals("image_url", userParts[1].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,AQID",
            userParts[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content,
        )
    }

    @Test
    fun `fallback request drops json output only`() {
        val request = Json.parseToJsonElement(
            recognizer.buildRequest(image, categories, now, zone, jsonMode = false),
        ).jsonObject

        assertFalse("response_format" in request)
        assertEquals("deepseek-flash", request.getValue("model").jsonPrimitive.content)
        assertEquals(2, request.getValue("messages").jsonArray.size)
    }

    @Test
    fun `blank api key fails without a request`() = runBlocking {
        val result = recognizer.recognize(image, categories, apiKey = " ", nowMillis = now, zoneId = zone)

        assertTrue(result is ScreenshotRecognitionResult.Failed)
    }
}
