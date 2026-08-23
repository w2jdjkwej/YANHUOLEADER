package com.yanhuozhang.offline.data

import androidx.room.TypeConverter
import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.EventSource
import com.yanhuozhang.offline.core.ObservationKind
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.StatisticsEffect
import com.yanhuozhang.offline.core.TransactionDirection

class RoomConverters {
    @TypeConverter fun accountingTypeToString(value: AccountingType?): String? = value?.name
    @TypeConverter fun stringToAccountingType(value: String?): AccountingType? = value?.let(AccountingType::valueOf)

    @TypeConverter fun directionToString(value: TransactionDirection?): String? = value?.name
    @TypeConverter fun stringToDirection(value: String?): TransactionDirection? = value?.let(TransactionDirection::valueOf)

    @TypeConverter fun effectToString(value: StatisticsEffect?): String? = value?.name
    @TypeConverter fun stringToEffect(value: String?): StatisticsEffect? = value?.let(StatisticsEffect::valueOf)

    @TypeConverter fun channelToString(value: PaymentChannel?): String? = value?.name
    @TypeConverter fun stringToChannel(value: String?): PaymentChannel? = value?.let(PaymentChannel::valueOf)

    @TypeConverter fun eventSourceToString(value: EventSource?): String? = value?.name
    @TypeConverter fun stringToEventSource(value: String?): EventSource? = value?.let(EventSource::valueOf)

    @TypeConverter fun observationKindToString(value: ObservationKind?): String? = value?.name
    @TypeConverter fun stringToObservationKind(value: String?): ObservationKind? = value?.let(ObservationKind::valueOf)

    @TypeConverter fun ledgerStatusToString(value: LedgerStatus?): String? = value?.name
    @TypeConverter fun stringToLedgerStatus(value: String?): LedgerStatus? = value?.let(LedgerStatus::valueOf)

    @TypeConverter fun eventStatusToString(value: EventStatus?): String? = value?.name
    @TypeConverter fun stringToEventStatus(value: String?): EventStatus? = value?.let(EventStatus::valueOf)
}
