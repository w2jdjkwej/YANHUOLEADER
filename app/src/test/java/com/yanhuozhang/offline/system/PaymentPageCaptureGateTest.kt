package com.yanhuozhang.offline.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentPageCaptureGateTest {
    @Test
    fun `one visible success page is emitted once`() {
        val gate = PaymentPageCaptureGate()

        assertTrue(gate.shouldCapture("吉林大学|1000"))
        assertFalse(gate.shouldCapture("吉林大学|1000"))
    }

    @Test
    fun `leaving success page allows identical next payment`() {
        val gate = PaymentPageCaptureGate()
        gate.shouldCapture("吉林大学|1000")

        gate.markPageAbsent()

        assertTrue(gate.shouldCapture("吉林大学|1000"))
    }

    @Test
    fun `different visible payment is captured`() {
        val gate = PaymentPageCaptureGate()
        gate.shouldCapture("吉林大学|1000")

        assertTrue(gate.shouldCapture("食堂|1250"))
    }

    @Test
    fun `blank and enriched reads of one visible page share an event id`() {
        val gate = PaymentPageCaptureGate()

        assertTrue(gate.shouldCapture("page|5290|false"))
        val blankReadEventId = gate.eventIdFor(amountCents = 5_290, observedAtMillis = 1_000)
        assertTrue(gate.shouldCapture("page|5290|true"))
        val enrichedReadEventId = gate.eventIdFor(amountCents = 5_290, observedAtMillis = 1_500)

        assertEquals(blankReadEventId, enrichedReadEventId)
    }

    @Test
    fun `leaving the page starts a new event id`() {
        val gate = PaymentPageCaptureGate()
        val firstEventId = gate.eventIdFor(amountCents = 5_290, observedAtMillis = 1_000)

        gate.markPageAbsent()
        val secondEventId = gate.eventIdFor(amountCents = 5_290, observedAtMillis = 2_000)

        assertTrue(firstEventId != secondEventId)
    }
}
