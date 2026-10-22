package com.yanhuozhang.offline.recognition

import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.TransactionDirection
import com.yanhuozhang.offline.data.CategoryEntity
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 把 DeepSeek 返回的 JSON 整理成可回填表单的 [RecognizedTransaction]。
 *
 * 模型偶尔会把数字写成字符串、带货币符号或给出不合理的时间，这里只做格式兜底，
 * 不判断内容对错——最终由用户在表单里核对。
 */
internal object ScreenshotResponseParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val timePattern = Regex(
        """(\d{4})\D(\d{1,2})\D(\d{1,2})(?:\D+(\d{1,2}):(\d{2})(?::(\d{2}))?)?""",
    )
    private val amountNoise = Regex("""[¥￥元,，\s+＋\-－]""")
    private val warningTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private const val FUTURE_TOLERANCE_MILLIS = 10 * 60 * 1000L
    private const val MAX_PAST_MILLIS = 366L * 24 * 60 * 60 * 1000
    private const val MAX_COUNTERPARTY_LENGTH = 40
    private const val MAX_BASIS_LENGTH = 60
    private const val MAX_NOTE_LENGTH = 80
    private const val MAX_WARNING_LENGTH = 60
    private const val MAX_MODEL_WARNINGS = 3

    fun parseResponse(
        responseText: String,
        categories: List<CategoryEntity>,
        nowMillis: Long,
        zoneId: ZoneId,
    ): ScreenshotRecognitionResult {
        val content = try {
            val response = json.parseToJsonElement(responseText).jsonObject
            response["choices"]?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.contentOrNull
        } catch (_: Exception) {
            null
        }
        if (content.isNullOrBlank()) {
            return ScreenshotRecognitionResult.Failed("DeepSeek 未返回识别结果，请重试")
        }
        val result = try {
            extractJsonObject(content)?.let { text -> json.parseToJsonElement(text).jsonObject }
        } catch (_: Exception) {
            null
        } ?: return ScreenshotRecognitionResult.Failed("DeepSeek 返回内容无法解析，请重试")
        return sanitize(result, categories, nowMillis, zoneId)
    }

    private fun sanitize(
        result: JsonObject,
        categories: List<CategoryEntity>,
        nowMillis: Long,
        zoneId: ZoneId,
    ): ScreenshotRecognitionResult {
        val kind = result.text("kind")?.uppercase()
        when (kind) {
            "NOT_TRANSACTION" ->
                return ScreenshotRecognitionResult.Failed("这张截图里没有找到已完成的交易")
            "MULTIPLE" ->
                return ScreenshotRecognitionResult.Failed("截图里有多笔交易，请打开其中一笔的详情页后再截图")
        }
        val amountCents = parseAmountCents(result.text("amount"))
        val modelDirection = when (result.text("direction")?.uppercase()) {
            "OUT" -> TransactionDirection.OUT
            "IN" -> TransactionDirection.IN
            else -> null
        }
        val accountingType = when (kind) {
            "EXPENSE" -> AccountingType.EXPENSE
            "INCOME" -> AccountingType.INCOME
            "REFUND" -> AccountingType.REFUND
            "TRANSFER" -> AccountingType.TRANSFER
            else -> {
                if (amountCents == null) {
                    return ScreenshotRecognitionResult.Failed("没有从截图中识别出交易信息")
                }
                if (modelDirection == TransactionDirection.IN) AccountingType.INCOME else AccountingType.EXPENSE
            }
        }
        // 与表单切换交易类型时的规则保持一致：只有转账需要单独区分转入转出。
        val direction = when (accountingType) {
            AccountingType.INCOME -> TransactionDirection.IN
            AccountingType.EXPENSE,
            AccountingType.REFUND,
            -> TransactionDirection.OUT
            AccountingType.TRANSFER,
            AccountingType.FLOW,
            -> modelDirection ?: TransactionDirection.OUT
        }

        val warnings = mutableListOf<String>()
        val transactionTimeMillis = parseTransactionTime(
            text = result.text("transactionTime"),
            nowMillis = nowMillis,
            zoneId = zoneId,
            warnings = warnings,
        )

        val counterparty = result.text("counterparty")?.clean(MAX_COUNTERPARTY_LENGTH)
        val counterpartyInferred = counterparty != null &&
            (result["counterpartyInferred"] as? JsonPrimitive)?.booleanOrNull == true
        val counterpartyBasis = if (counterpartyInferred) {
            result.text("counterpartyBasis")?.clean(MAX_BASIS_LENGTH)
        } else {
            null
        }

        val paymentChannel = when (result.text("channel")?.uppercase()) {
            "WECHAT" -> PaymentChannel.WECHAT
            "BANK_CARD" -> PaymentChannel.BANK_CARD
            else -> PaymentChannel.OTHER
        }
        // 支付宝、抖音等没有单独的支付渠道，平台名写进备注；微信已经由渠道体现，不再重复。
        val platform = result.text("platform")?.takeIf { paymentChannel != PaymentChannel.WECHAT }
        val note = listOfNotNull(platform, result.text("paymentMethod"), result.text("itemsSummary"))
            .mapNotNull { part -> part.clean(MAX_NOTE_LENGTH) }
            .distinct()
            .joinToString(" · ")
            .take(MAX_NOTE_LENGTH)

        val categoryType = categoryTypeFor(accountingType)
        val suggestedCategoryId = result.long("categoryId")?.takeIf { id ->
            categories.any { category ->
                category.id == id && category.isActive && category.accountingType == categoryType
            }
        }

        val modelWarnings = (result["warnings"] as? JsonArray).orEmpty()
            .mapNotNull { item -> (item as? JsonPrimitive)?.contentOrNull?.clean(MAX_WARNING_LENGTH) }
            .take(MAX_MODEL_WARNINGS)

        return ScreenshotRecognitionResult.Recognized(
            RecognizedTransaction(
                accountingType = accountingType,
                direction = direction,
                amountCents = amountCents,
                transactionTimeMillis = transactionTimeMillis,
                counterparty = counterparty,
                counterpartyInferred = counterpartyInferred,
                counterpartyBasis = counterpartyBasis,
                paymentChannel = paymentChannel,
                note = note,
                categoryId = suggestedCategoryId,
                warnings = (warnings + modelWarnings).distinct(),
            ),
        )
    }

    /** 与记账表单的分类筛选一致：退款用支出分类，资金流转用转账分类。 */
    fun categoryTypeFor(accountingType: AccountingType): AccountingType {
        return when (accountingType) {
            AccountingType.INCOME -> AccountingType.INCOME
            AccountingType.TRANSFER,
            AccountingType.FLOW,
            -> AccountingType.TRANSFER
            AccountingType.EXPENSE,
            AccountingType.REFUND,
            -> AccountingType.EXPENSE
        }
    }

    internal fun parseAmountCents(text: String?): Long? {
        val normalized = text?.replace(amountNoise, "")?.takeIf { value -> value.isNotEmpty() } ?: return null
        val amount = normalized.toBigDecimalOrNull() ?: return null
        if (amount.stripTrailingZeros().scale() > 2) {
            return null
        }
        return try {
            amount.setScale(2).movePointRight(2).longValueExact().takeIf { cents -> cents > 0 }
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun parseTransactionTime(
        text: String?,
        nowMillis: Long,
        zoneId: ZoneId,
        warnings: MutableList<String>,
    ): Long? {
        if (text == null) {
            return null
        }
        val match = timePattern.find(text)
        if (match == null) {
            warnings += "无法解析截图中的时间“${text.take(20)}”，已使用当前时间"
            return null
        }
        val (year, month, day, hour, minute, second) = match.destructured
        val dateOnly = hour.isEmpty()
        val dateTime = try {
            LocalDateTime.of(
                year.toInt(),
                month.toInt(),
                day.toInt(),
                if (dateOnly) 12 else hour.toInt(),
                if (dateOnly) 0 else minute.toInt(),
                second.ifEmpty { "0" }.toInt(),
            )
        } catch (_: DateTimeException) {
            warnings += "截图中的时间“${text.take(20)}”无效，已使用当前时间"
            return null
        }
        val millis = dateTime.atZone(zoneId).toInstant().toEpochMilli()
        if (millis > nowMillis + FUTURE_TOLERANCE_MILLIS || millis < nowMillis - MAX_PAST_MILLIS) {
            warnings += "识别出的时间 ${warningTimeFormatter.format(dateTime)} 不合理，已使用当前时间"
            return null
        }
        if (dateOnly) {
            warnings += "截图只有日期没有时刻，时间按 12:00 填写"
        }
        return millis
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

    private fun JsonObject.text(key: String): String? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        if (primitive is JsonNull) {
            return null
        }
        return primitive.content.trim().takeIf { value -> value.isNotEmpty() && value != "null" }
    }

    private fun JsonObject.long(key: String): Long? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.contentOrNull?.trim()?.toLongOrNull()
    }

    private fun String.clean(maxLength: Int): String? {
        return replace(Regex("""\s+"""), " ").trim().take(maxLength).ifBlank { null }
    }
}
