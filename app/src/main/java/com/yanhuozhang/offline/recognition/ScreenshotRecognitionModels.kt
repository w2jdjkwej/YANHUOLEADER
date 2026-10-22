package com.yanhuozhang.offline.recognition

import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.TransactionDirection

/** 从截图识别出的一笔交易，只用于回填“记录一笔”表单，由用户核对后保存。 */
data class RecognizedTransaction(
    val accountingType: AccountingType,
    val direction: TransactionDirection,
    /** 截图里找不到可用金额时为 null。 */
    val amountCents: Long?,
    /** 截图里没有可用时间、或时间明显不合理时为 null，表单沿用当前时间。 */
    val transactionTimeMillis: Long?,
    val counterparty: String?,
    /** 交易对象不是截图上写明的，而是模型根据网址、页面标题等线索推测的。 */
    val counterpartyInferred: Boolean,
    val counterpartyBasis: String?,
    val paymentChannel: PaymentChannel,
    val note: String,
    val categoryId: Long?,
    val warnings: List<String>,
)

sealed interface ScreenshotRecognitionResult {
    data class Recognized(val transaction: RecognizedTransaction) : ScreenshotRecognitionResult

    /** 识别失败、不是交易截图或截图里有多笔交易；[reason] 直接展示给用户。 */
    data class Failed(val reason: String) : ScreenshotRecognitionResult
}

sealed interface ScreenshotRecognitionState {
    data object Idle : ScreenshotRecognitionState

    data class Running(val requestId: Long) : ScreenshotRecognitionState

    data class Finished(
        val requestId: Long,
        val result: ScreenshotRecognitionResult,
    ) : ScreenshotRecognitionState
}

/** 发给 DeepSeek 的图片，已按 [ScreenshotImagePlanner] 处理过。 */
class EncodedScreenshot(
    val mimeType: String,
    val bytes: ByteArray,
) {
    fun toDataUrl(): String {
        return "data:$mimeType;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
    }
}
