package com.yanhuozhang.offline.recognition

import com.yanhuozhang.offline.core.TransactionDirection
import com.yanhuozhang.offline.data.LedgerRecordEntity
import com.yanhuozhang.offline.data.LedgerStatus
import kotlin.math.abs

/**
 * 截图记账前提示“可能已经记过”。
 *
 * 不区分支付渠道：用银行卡付的抖音订单可能已经由银行短信入账，微信支付也可能已自动记账。
 * 只提示，不阻止保存。
 */
internal object PossibleDuplicateFinder {
    /** 截图里有交易时间时，同一笔交易的已有账单时间通常只差几分钟。 */
    const val KNOWN_TIME_WINDOW_MILLIS = 10 * 60 * 1000L

    /** 截图里没有时间、表单用的是当前时间时，放宽到前后一天。 */
    const val UNKNOWN_TIME_WINDOW_MILLIS = 24 * 60 * 60 * 1000L

    private const val MAX_RESULTS = 3

    fun find(
        records: List<LedgerRecordEntity>,
        amountCents: Long,
        direction: TransactionDirection,
        transactionTimeMillis: Long,
        windowMillis: Long,
    ): List<LedgerRecordEntity> {
        return records.asSequence()
            .filter { record ->
                record.status == LedgerStatus.ACTIVE &&
                    record.amountCents == amountCents &&
                    record.direction == direction &&
                    abs(record.transactionTimeMillis - transactionTimeMillis) <= windowMillis
            }
            .sortedBy { record -> abs(record.transactionTimeMillis - transactionTimeMillis) }
            .take(MAX_RESULTS)
            .toList()
    }
}
