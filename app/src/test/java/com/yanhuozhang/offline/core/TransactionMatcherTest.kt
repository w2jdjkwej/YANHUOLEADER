package com.yanhuozhang.offline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionMatcherTest {
    private val incoming = ParsedObservation(
        source = EventSource.WECHAT_SMS,
        sourceEventId = "sms-1",
        rawText = "付款成功8.70元",
        amountCents = 870,
        direction = TransactionDirection.OUT,
        kind = ObservationKind.PAYMENT,
        transactionTimeMillis = 1_000_000,
        observedAtMillis = 1_000_500,
        canAutoRecord = true,
    )

    @Test
    fun `same source fingerprint is duplicate`() {
        val decision = TransactionMatcher.match(
            incoming = incoming,
            sourceFingerprintAlreadyExists = true,
            candidates = emptyList(),
        )

        assertEquals(MatchDecision.DuplicateEvent, decision)
    }

    @Test
    fun `single close candidate is attached`() {
        val candidate = MatchCandidate(
            recordId = 42,
            amountCents = 870,
            direction = TransactionDirection.OUT,
            transactionTimeMillis = 1_002_000,
            paymentChannel = PaymentChannel.WECHAT,
        )

        val decision = TransactionMatcher.match(incoming, false, listOf(candidate))

        assertEquals(MatchDecision.AttachToRecord(42), decision)
    }

    @Test
    fun `multiple close same amount candidates require confirmation`() {
        val candidates = listOf(
            MatchCandidate(1, 870, TransactionDirection.OUT, 999_000, PaymentChannel.WECHAT),
            MatchCandidate(2, 870, TransactionDirection.OUT, 1_001_000, PaymentChannel.WECHAT),
        )

        val decision = TransactionMatcher.match(incoming, false, candidates)

        assertTrue(decision is MatchDecision.NeedConfirmation)
    }

    @Test
    fun `same source candidate requires confirmation instead of silent merge`() {
        val candidate = MatchCandidate(
            recordId = 42,
            amountCents = 870,
            direction = TransactionDirection.OUT,
            transactionTimeMillis = 1_002_000,
            paymentChannel = PaymentChannel.WECHAT,
            observedSources = setOf(EventSource.WECHAT_SMS),
        )

        val decision = TransactionMatcher.match(incoming, false, listOf(candidate))

        assertTrue(decision is MatchDecision.NeedConfirmation)
    }

    @Test
    fun `same payment page session can fill a missing counterparty`() {
        val pageObservation = incoming.copy(
            source = EventSource.WECHAT_PAYMENT_PAGE,
            sourceEventId = "page-session-1",
            counterparty = "京东",
        )
        val candidate = MatchCandidate(
            recordId = 42,
            amountCents = 870,
            direction = TransactionDirection.OUT,
            transactionTimeMillis = 1_002_000,
            paymentChannel = PaymentChannel.WECHAT,
            observedSources = setOf(EventSource.WECHAT_PAYMENT_PAGE),
            incompletePaymentPageEventIds = setOf("page-session-1"),
        )

        val decision = TransactionMatcher.match(pageObservation, false, listOf(candidate))

        assertEquals(MatchDecision.AttachToRecord(42), decision)
    }

    @Test
    fun `different payment page session still requires confirmation`() {
        val pageObservation = incoming.copy(
            source = EventSource.WECHAT_PAYMENT_PAGE,
            sourceEventId = "page-session-2",
            counterparty = "京东",
        )
        val candidate = MatchCandidate(
            recordId = 42,
            amountCents = 870,
            direction = TransactionDirection.OUT,
            transactionTimeMillis = 1_002_000,
            paymentChannel = PaymentChannel.WECHAT,
            observedSources = setOf(EventSource.WECHAT_PAYMENT_PAGE),
            incompletePaymentPageEventIds = setOf("page-session-1"),
        )

        val decision = TransactionMatcher.match(pageObservation, false, listOf(candidate))

        assertEquals(MatchDecision.NeedConfirmation("同一来源存在相近的同金额交易"), decision)
    }

    @Test
    fun `page session id selects the correct record from same amount candidates`() {
        val pageObservation = incoming.copy(
            source = EventSource.WECHAT_PAYMENT_PAGE,
            sourceEventId = "page-session-1",
            counterparty = "京东",
        )
        val candidates = listOf(
            MatchCandidate(
                recordId = 41,
                amountCents = 870,
                direction = TransactionDirection.OUT,
                transactionTimeMillis = 1_001_000,
                paymentChannel = PaymentChannel.WECHAT,
            ),
            MatchCandidate(
                recordId = 42,
                amountCents = 870,
                direction = TransactionDirection.OUT,
                transactionTimeMillis = 1_002_000,
                paymentChannel = PaymentChannel.WECHAT,
                observedSources = setOf(EventSource.WECHAT_PAYMENT_PAGE),
                incompletePaymentPageEventIds = setOf("page-session-1"),
            ),
        )

        val decision = TransactionMatcher.match(pageObservation, false, candidates)

        assertEquals(MatchDecision.AttachToRecord(42), decision)
    }

    @Test
    fun `clear payment without candidates creates record`() {
        val decision = TransactionMatcher.match(incoming, false, emptyList())

        assertEquals(MatchDecision.CreateRecord, decision)
    }

    @Test
    fun `page counterparty matching existing counterparty attaches`() {
        val pageObservation = incoming.copy(
            source = EventSource.WECHAT_PAYMENT_PAGE,
            counterparty = "吉林大学",
        )
        val candidate = MatchCandidate(
            recordId = 42,
            amountCents = 870,
            direction = TransactionDirection.OUT,
            transactionTimeMillis = 1_002_000,
            paymentChannel = PaymentChannel.WECHAT,
            counterparty = "吉林大学",
        )

        val decision = TransactionMatcher.match(pageObservation, false, listOf(candidate))

        assertEquals(MatchDecision.AttachToRecord(42), decision)
    }

    @Test
    fun `page counterparty conflicting with existing record requires confirmation`() {
        val pageObservation = incoming.copy(
            source = EventSource.WECHAT_PAYMENT_PAGE,
            counterparty = "吉林大学",
        )
        val candidate = MatchCandidate(
            recordId = 42,
            amountCents = 870,
            direction = TransactionDirection.OUT,
            transactionTimeMillis = 1_002_000,
            paymentChannel = PaymentChannel.WECHAT,
            counterparty = "已有商户",
        )

        val decision = TransactionMatcher.match(pageObservation, false, listOf(candidate))

        assertEquals(MatchDecision.NeedConfirmation("交易对象与已有账单不一致"), decision)
    }

    @Test
    fun `red packet requires confirmation even with a same amount candidate`() {
        val redPacket = incoming.copy(
            source = EventSource.WECHAT_PAYMENT_PAGE,
            rawText = "微信红包已发送\n￥8.70",
            kind = ObservationKind.RED_PACKET,
            canAutoRecord = false,
        )
        val candidate = MatchCandidate(
            recordId = 42,
            amountCents = 870,
            direction = TransactionDirection.OUT,
            transactionTimeMillis = 1_002_000,
            paymentChannel = PaymentChannel.WECHAT,
        )

        val decision = TransactionMatcher.match(redPacket, false, listOf(candidate))

        assertEquals(MatchDecision.NeedConfirmation("红包需要确认"), decision)
    }
}
