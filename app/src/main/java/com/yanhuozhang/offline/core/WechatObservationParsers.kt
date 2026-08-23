package com.yanhuozhang.offline.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object WechatNotificationParser {
    fun parseOrNull(raw: RawObservation): ParsedObservation? {
        if (raw.source != EventSource.WECHAT_NOTIFICATION) {
            return null
        }
        if (raw.title?.contains("微信支付") != true) {
            return null
        }

        val amountCents = MoneyParser.parseCents(raw.content)
        val isClearPayment = raw.content.contains("已支付") ||
            raw.content.contains("支付成功") ||
            raw.content.contains("付款成功")

        return ParsedObservation(
            source = raw.source,
            sourceEventId = raw.sourceEventId,
            rawText = listOfNotNull(raw.title, raw.content).joinToString("\n"),
            amountCents = amountCents,
            direction = if (isClearPayment) TransactionDirection.OUT else null,
            kind = if (isClearPayment) ObservationKind.PAYMENT else ObservationKind.UNKNOWN,
            transactionTimeMillis = if (isClearPayment) raw.observedAtMillis else null,
            observedAtMillis = raw.observedAtMillis,
            canAutoRecord = isClearPayment && amountCents != null,
        )
    }

    fun parse(raw: RawObservation): ParsedObservation {
        return requireNotNull(parseOrNull(raw)) { "不是微信支付通知" }
    }
}

object WechatSmsParser {
    private val dateTimePattern = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun parseOrNull(raw: RawObservation, zoneId: ZoneId): ParsedObservation? {
        if (raw.source != EventSource.WECHAT_SMS) {
            return null
        }
        if (!raw.content.contains("微信支付") || !raw.content.contains("付款成功")) {
            return null
        }

        val amountCents = MoneyParser.parseCents(raw.content) ?: return null
        val transactionTimeMillis = parseTransactionTime(raw.content, zoneId) ?: return null

        return ParsedObservation(
            source = raw.source,
            sourceEventId = raw.sourceEventId,
            rawText = raw.content,
            amountCents = amountCents,
            direction = TransactionDirection.OUT,
            kind = ObservationKind.PAYMENT,
            transactionTimeMillis = transactionTimeMillis,
            observedAtMillis = raw.observedAtMillis,
            canAutoRecord = true,
        )
    }

    fun parse(raw: RawObservation, zoneId: ZoneId): ParsedObservation {
        return requireNotNull(parseOrNull(raw, zoneId)) { "不是已支持的微信支付结果短信" }
    }

    private fun parseTransactionTime(text: String, zoneId: ZoneId): Long? {
        val dateTimeText = dateTimePattern.find(text)?.value ?: return null
        return try {
            LocalDateTime.parse(dateTimeText, dateTimeFormatter)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
