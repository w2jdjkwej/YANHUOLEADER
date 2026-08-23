package com.yanhuozhang.offline.core

import kotlin.math.abs

data class MatchCandidate(
    val recordId: Long,
    val amountCents: Long,
    val direction: TransactionDirection,
    val transactionTimeMillis: Long,
    val paymentChannel: PaymentChannel,
    val observedSources: Set<EventSource> = emptySet(),
    val counterparty: String? = null,
    val incompletePaymentPageEventIds: Set<String> = emptySet(),
)

sealed interface MatchDecision {
    data object DuplicateEvent : MatchDecision
    data object CreateRecord : MatchDecision
    data class AttachToRecord(val recordId: Long) : MatchDecision
    data class NeedConfirmation(val reason: String) : MatchDecision
}

object TransactionMatcher {
    const val MATCH_WINDOW_MILLIS = 10 * 60 * 1000L

    fun match(
        incoming: ParsedObservation,
        sourceFingerprintAlreadyExists: Boolean,
        candidates: List<MatchCandidate>,
    ): MatchDecision {
        if (sourceFingerprintAlreadyExists) {
            return MatchDecision.DuplicateEvent
        }
        if (incoming.kind == ObservationKind.RED_PACKET) {
            return MatchDecision.NeedConfirmation("红包需要确认")
        }

        val incomingAmount = incoming.amountCents
        val incomingDirection = incoming.direction
        val incomingTime = incoming.transactionTimeMillis
        if (incomingAmount == null || incomingDirection == null || incomingTime == null) {
            return MatchDecision.NeedConfirmation("交易信息不完整")
        }

        val matchingCandidates = candidates.filter { candidate ->
            candidate.amountCents == incomingAmount &&
                candidate.direction == incomingDirection &&
                candidate.paymentChannel == PaymentChannel.WECHAT &&
                abs(candidate.transactionTimeMillis - incomingTime) <= MATCH_WINDOW_MILLIS
        }
        val incomingCounterparty = incoming.counterparty?.trim().orEmpty()
        val samePageEnrichmentCandidate = matchingCandidates.singleOrNull { candidate ->
            val recordCounterpartyCanBeFilled = candidate.counterparty == null || candidate.counterparty.isEmpty()
            incoming.source == EventSource.WECHAT_PAYMENT_PAGE &&
                incomingCounterparty.isNotEmpty() &&
                recordCounterpartyCanBeFilled &&
                incoming.sourceEventId in candidate.incompletePaymentPageEventIds
        }
        if (samePageEnrichmentCandidate != null) {
            return MatchDecision.AttachToRecord(samePageEnrichmentCandidate.recordId)
        }

        if (matchingCandidates.size == 1) {
            val candidate = matchingCandidates.first()
            val existingCounterparty = candidate.counterparty?.trim().orEmpty()
            if (incomingCounterparty.isNotEmpty() && existingCounterparty.isNotEmpty() &&
                incomingCounterparty != existingCounterparty
            ) {
                return MatchDecision.NeedConfirmation("交易对象与已有账单不一致")
            }
            if (incoming.source in candidate.observedSources) {
                return MatchDecision.NeedConfirmation("同一来源存在相近的同金额交易")
            }
            return MatchDecision.AttachToRecord(candidate.recordId)
        }
        if (matchingCandidates.size > 1) {
            return MatchDecision.NeedConfirmation("存在多笔同金额候选交易")
        }
        if (incoming.canAutoRecord) {
            return MatchDecision.CreateRecord
        }
        return MatchDecision.NeedConfirmation("识别置信度不足")
    }
}
