package com.yanhuozhang.offline.system

class PaymentPageCaptureGate {
    private var activeSignature: String? = null
    private var activeAmountCents: Long? = null
    private var activeSourceEventId: String? = null

    fun shouldCapture(signature: String): Boolean {
        if (signature == activeSignature) {
            return false
        }
        activeSignature = signature
        return true
    }

    fun eventIdFor(amountCents: Long, observedAtMillis: Long): String {
        val currentEventId = activeSourceEventId
        if (currentEventId != null && activeAmountCents == amountCents) {
            return currentEventId
        }

        val newEventId = "page-$observedAtMillis-$amountCents"
        activeAmountCents = amountCents
        activeSourceEventId = newEventId
        return newEventId
    }

    fun markPageAbsent() {
        activeSignature = null
        activeAmountCents = null
        activeSourceEventId = null
    }
}
