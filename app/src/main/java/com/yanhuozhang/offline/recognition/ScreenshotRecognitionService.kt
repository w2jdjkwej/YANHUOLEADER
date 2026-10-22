package com.yanhuozhang.offline.recognition

import com.yanhuozhang.offline.data.LedgerRepository
import com.yanhuozhang.offline.settings.SettingsRepository
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 截图识别的完整流程：检查 DeepSeek 配置、处理图片、调用模型，再用本地规则补分类。
 *
 * 分类优先级与自动记账一致：个人对象规则、本地关键词，都没命中时才用模型建议的分类。
 */
class ScreenshotRecognitionService(
    private val settingsRepository: SettingsRepository,
    private val ledgerRepository: LedgerRepository,
    private val imageEncoder: ScreenshotImageEncoder,
    private val recognizer: ScreenshotRecognizer,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun recognize(imageBytes: ByteArray): ScreenshotRecognitionResult {
        val settings = settingsRepository.settings.first()
        if (!settings.deepSeekEnabled || settings.deepSeekApiKey.isBlank()) {
            return ScreenshotRecognitionResult.Failed("请先在设置中启用 DeepSeek 并填写 API Key")
        }
        val image = try {
            withContext(Dispatchers.Default) { imageEncoder.encode(imageBytes) }
        } catch (error: IllegalArgumentException) {
            return ScreenshotRecognitionResult.Failed(error.message ?: "无法读取这张图片")
        } catch (_: OutOfMemoryError) {
            return ScreenshotRecognitionResult.Failed("图片太大，无法处理")
        }
        val categories = ledgerRepository.categories.first().filter { category ->
            category.isActive && category.name != UNCATEGORIZED_NAME
        }
        val result = recognizer.recognize(
            image = image,
            categories = categories,
            apiKey = settings.deepSeekApiKey,
            nowMillis = clock(),
            zoneId = zoneId,
        )
        if (result !is ScreenshotRecognitionResult.Recognized) {
            return result
        }
        val transaction = result.transaction
        val localCategoryId = ledgerRepository.suggestCategoryId(
            counterparty = transaction.counterparty,
            note = transaction.note,
            accountingType = transaction.accountingType,
            direction = transaction.direction,
            paymentChannel = transaction.paymentChannel,
        ) ?: return result
        return ScreenshotRecognitionResult.Recognized(transaction.copy(categoryId = localCategoryId))
    }

    private companion object {
        const val UNCATEGORIZED_NAME = "未分类"
    }
}
