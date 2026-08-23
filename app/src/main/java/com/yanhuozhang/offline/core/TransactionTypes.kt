package com.yanhuozhang.offline.core

import kotlinx.serialization.Serializable

@Serializable
enum class EventSource {
    WECHAT_NOTIFICATION,
    WECHAT_SMS,
    WECHAT_PAYMENT_PAGE,
    BANK_SMS,
    MANUAL,
}

@Serializable
enum class TransactionDirection {
    IN,
    OUT,
}

@Serializable
enum class ObservationKind {
    PAYMENT,
    TRANSFER,
    RED_PACKET,
    REFUND,
    UNKNOWN,
}

@Serializable
enum class PaymentChannel {
    WECHAT,
    BANK_CARD,
    CASH,
    OTHER,
}

@Serializable
enum class AccountingType {
    INCOME,
    EXPENSE,
    TRANSFER,
    FLOW,
    REFUND,
}

@Serializable
enum class StatisticsEffect {
    ADD_INCOME,
    ADD_EXPENSE,
    SUBTRACT_EXPENSE,
    NONE,
}

data class RawObservation(
    val source: EventSource,
    val sourceEventId: String,
    val title: String?,
    val content: String,
    val observedAtMillis: Long,
)

data class ParsedObservation(
    val source: EventSource,
    val sourceEventId: String,
    val rawText: String,
    val amountCents: Long?,
    val direction: TransactionDirection?,
    val kind: ObservationKind,
    val transactionTimeMillis: Long?,
    val observedAtMillis: Long,
    val canAutoRecord: Boolean,
    val counterparty: String? = null,
)
