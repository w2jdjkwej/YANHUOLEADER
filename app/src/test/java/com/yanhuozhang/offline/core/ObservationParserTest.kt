package com.yanhuozhang.offline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ObservationParserTest {
    @Test
    fun `known wechat payment notification is safe to auto record`() {
        val raw = RawObservation(
            source = EventSource.WECHAT_NOTIFICATION,
            sourceEventId = "wechat-key-1",
            title = "微信支付",
            content = "已支付￥14.80",
            observedAtMillis = 1_750_000_000_000,
        )

        val parsed = WechatNotificationParser.parse(raw)

        assertEquals(1480L, parsed.amountCents)
        assertEquals(TransactionDirection.OUT, parsed.direction)
        assertEquals(ObservationKind.PAYMENT, parsed.kind)
        assertTrue(parsed.canAutoRecord)
        assertEquals(raw.observedAtMillis, parsed.transactionTimeMillis)
    }

    @Test
    fun `wechat notification without clear payment wording is pending`() {
        val raw = RawObservation(
            source = EventSource.WECHAT_NOTIFICATION,
            sourceEventId = "wechat-key-2",
            title = "微信支付",
            content = "支付消息￥20.00",
            observedAtMillis = 1_750_000_000_000,
        )

        val parsed = WechatNotificationParser.parse(raw)

        assertEquals(2000L, parsed.amountCents)
        assertFalse(parsed.canAutoRecord)
        assertEquals(ObservationKind.UNKNOWN, parsed.kind)
    }

    @Test
    fun `unrelated notification contains no transaction`() {
        val raw = RawObservation(
            source = EventSource.WECHAT_NOTIFICATION,
            sourceEventId = "chat-key",
            title = "张三",
            content = "午饭吃什么？",
            observedAtMillis = 1_750_000_000_000,
        )

        assertNull(WechatNotificationParser.parseOrNull(raw))
    }

    @Test
    fun `wechat result sms extracts exact transaction time`() {
        val raw = RawObservation(
            source = EventSource.WECHAT_SMS,
            sourceEventId = "sms-1",
            title = null,
            content = "【微信支付】你手机号绑定的微信于2023-05-31 11:17:50付款成功8.70元。由于付款时手机网络不畅，本次支付结果使用短信提醒。",
            observedAtMillis = 1_685_500_000_000,
        )

        val parsed = WechatSmsParser.parse(raw, ZoneId.of("Asia/Shanghai"))
        val expectedTime = LocalDateTime.of(2023, 5, 31, 11, 17, 50)
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()

        assertEquals(870L, parsed.amountCents)
        assertEquals(expectedTime, parsed.transactionTimeMillis)
        assertEquals(TransactionDirection.OUT, parsed.direction)
        assertTrue(parsed.canAutoRecord)
    }
}
