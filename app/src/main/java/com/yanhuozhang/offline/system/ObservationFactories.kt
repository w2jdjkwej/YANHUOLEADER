package com.yanhuozhang.offline.system

import com.yanhuozhang.offline.core.EventSource
import com.yanhuozhang.offline.core.RawObservation
import java.security.MessageDigest

data class NotificationPayload(
    val packageName: String,
    val notificationKey: String,
    val title: String?,
    val text: String,
    val postedAtMillis: Long,
)

object NotificationObservationFactory {
    private const val WECHAT_PACKAGE = "com.tencent.mm"

    fun create(payload: NotificationPayload): RawObservation? {
        if (payload.packageName != WECHAT_PACKAGE) {
            return null
        }
        if (payload.title.isNullOrBlank() || payload.text.isBlank()) {
            return null
        }
        return RawObservation(
            source = EventSource.WECHAT_NOTIFICATION,
            sourceEventId = "${payload.notificationKey}:${payload.postedAtMillis}",
            title = payload.title,
            content = payload.text,
            observedAtMillis = payload.postedAtMillis,
        )
    }
}

object SmsObservationFactory {
    fun create(sender: String, body: String, receivedAtMillis: Long): RawObservation? {
        val source = when {
            body.contains("微信支付") && body.contains("付款成功") -> EventSource.WECHAT_SMS
            isBankTransactionSms(body) -> EventSource.BANK_SMS
            else -> return null
        }
        val sourceEventId = sha256("$sender|$receivedAtMillis|${normalize(body)}")
        return RawObservation(
            source = source,
            sourceEventId = sourceEventId,
            title = sender,
            content = body,
            observedAtMillis = receivedAtMillis,
        )
    }

    private fun isBankTransactionSms(body: String): Boolean {
        val hasDirection = listOf("支出", "消费", "扣款", "收入", "退款").any(body::contains)
        val hasChannel = listOf("微信", "微信支付", "财付通", "Tenpay", "TENPAY").any(body::contains)
        return hasDirection && hasChannel
    }

    private fun normalize(text: String): String {
        return text.filterNot { character -> character.isWhitespace() }
    }

    private fun sha256(text: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
