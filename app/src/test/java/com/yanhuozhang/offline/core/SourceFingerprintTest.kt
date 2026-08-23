package com.yanhuozhang.offline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceFingerprintTest {
    @Test
    fun `same source event produces same fingerprint`() {
        val raw = RawObservation(
            source = EventSource.WECHAT_NOTIFICATION,
            sourceEventId = "notification-key",
            title = "微信支付",
            content = "已支付￥14.80",
            observedAtMillis = 1_000,
        )

        assertEquals(SourceFingerprint.create(raw), SourceFingerprint.create(raw))
    }

    @Test
    fun `different source event id produces different fingerprint`() {
        val first = RawObservation(
            EventSource.WECHAT_NOTIFICATION,
            "key-1",
            "微信支付",
            "已支付￥14.80",
            1_000,
        )
        val second = first.copy(sourceEventId = "key-2")

        assertNotEquals(SourceFingerprint.create(first), SourceFingerprint.create(second))
    }

    @Test
    fun `normalizes harmless whitespace differences`() {
        val first = RawObservation(
            EventSource.WECHAT_SMS,
            "sms-1",
            null,
            "付款成功 8.70 元",
            1_000,
        )
        val second = first.copy(content = " 付款成功\n8.70　元 ")

        assertEquals(SourceFingerprint.create(first), SourceFingerprint.create(second))
    }
}
