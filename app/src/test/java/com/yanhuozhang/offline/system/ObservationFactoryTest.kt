package com.yanhuozhang.offline.system

import com.yanhuozhang.offline.core.EventSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObservationFactoryTest {
    @Test
    fun `wechat payment notification becomes raw observation`() {
        val raw = NotificationObservationFactory.create(
            NotificationPayload(
                packageName = "com.tencent.mm",
                notificationKey = "0|com.tencent.mm|1|null|100",
                title = "微信支付",
                text = "已支付￥14.80",
                postedAtMillis = 1_000,
            ),
        )

        assertEquals(EventSource.WECHAT_NOTIFICATION, raw?.source)
        assertEquals("微信支付", raw?.title)
        assertEquals("已支付￥14.80", raw?.content)
    }

    @Test
    fun `unrelated app notification is ignored`() {
        val raw = NotificationObservationFactory.create(
            NotificationPayload("com.example.chat", "key", "消息", "你好", 1_000),
        )

        assertNull(raw)
    }

    @Test
    fun `wechat result sms is classified separately from bank sms`() {
        val wechat = SmsObservationFactory.create(
            sender = "10690000",
            body = "【微信支付】微信于2023-05-31 11:17:50付款成功8.70元。",
            receivedAtMillis = 1_000,
        )
        val bank = SmsObservationFactory.create(
            sender = "95588",
            body = "您尾号1234卡支出18.00元，交易渠道财付通。",
            receivedAtMillis = 2_000,
        )

        assertEquals(EventSource.WECHAT_SMS, wechat?.source)
        assertEquals(EventSource.BANK_SMS, bank?.source)
        assertEquals(1_000L, wechat?.observedAtMillis)
    }

    @Test
    fun `ordinary personal sms is ignored`() {
        assertNull(SmsObservationFactory.create("10086", "您的话费余额充足", 1_000))
    }
}
