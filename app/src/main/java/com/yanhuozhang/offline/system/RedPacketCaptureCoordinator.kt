package com.yanhuozhang.offline.system

import com.yanhuozhang.offline.core.RedPacketChatPage

sealed interface RedPacketCaptureResult {
    data class Completed(val amountCents: Long) : RedPacketCaptureResult
}

class RedPacketCaptureCoordinator(
    private val candidateTimeoutMillis: Long = 90_000L,
    private val renderGraceMillis: Long = 5_000L,
    private val baselineMaximumAgeMillis: Long = 5 * 60_000L,
) {
    private data class TimedChatPage(
        val page: RedPacketChatPage,
        val observedAtMillis: Long,
    )

    private data class Candidate(
        val amountCents: Long,
        val baselineCards: Map<String, Int>,
        val createdAtMillis: Long,
        val returnedToChatAtMillis: Long? = null,
    )

    private var latestChatPage: TimedChatPage? = null
    private var candidate: Candidate? = null

    fun observeAmount(amountCents: Long, nowMillis: Long): Boolean {
        expireCandidate(nowMillis)
        if (amountCents <= 0) {
            candidate = null
            return false
        }

        val chatPage = latestChatPage ?: return false
        if (nowMillis - chatPage.observedAtMillis > baselineMaximumAgeMillis) {
            return false
        }

        candidate = Candidate(
            amountCents = amountCents,
            baselineCards = chatPage.page.outgoingCards,
            createdAtMillis = nowMillis,
        )
        return true
    }

    fun observeChat(page: RedPacketChatPage, nowMillis: Long): RedPacketCaptureResult? {
        expireCandidate(nowMillis)
        val activeCandidate = candidate
        if (activeCandidate != null) {
            val returnedAtMillis = activeCandidate.returnedToChatAtMillis
            if (returnedAtMillis != null && nowMillis - returnedAtMillis > renderGraceMillis) {
                candidate = null
            } else if (hasAddedCard(activeCandidate.baselineCards, page.outgoingCards)) {
                candidate = null
                latestChatPage = TimedChatPage(page, nowMillis)
                return RedPacketCaptureResult.Completed(activeCandidate.amountCents)
            } else if (returnedAtMillis == null) {
                candidate = activeCandidate.copy(returnedToChatAtMillis = nowMillis)
            }
        }

        latestChatPage = TimedChatPage(page, nowMillis)
        return null
    }

    private fun expireCandidate(nowMillis: Long) {
        val activeCandidate = candidate ?: return
        if (nowMillis - activeCandidate.createdAtMillis > candidateTimeoutMillis) {
            candidate = null
        }
    }

    private fun hasAddedCard(
        baselineCards: Map<String, Int>,
        currentCards: Map<String, Int>,
    ): Boolean {
        return currentCards.any { (fingerprint, count) ->
            count > baselineCards.getOrDefault(fingerprint, 0)
        }
    }
}
