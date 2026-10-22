package com.yanhuozhang.offline.system

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.Display
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.yanhuozhang.offline.core.OcrMerchantExtraction
import com.yanhuozhang.offline.core.OcrTextLine
import com.yanhuozhang.offline.core.WechatOcrMerchantExtractor
import com.yanhuozhang.offline.settings.MerchantOcrSettings
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class MerchantOcrRequest(
    val sourceEventId: String,
    val amountCents: Long,
    val observedAtMillis: Long,
    val settings: MerchantOcrSettings,
)

/** [merchant] 只有在金额交叉校验通过并找到商家时才非空；[summary] 写入诊断日志。 */
internal data class MerchantOcrResult(
    val merchant: String?,
    val summary: String,
)

/**
 * 截取当前屏幕、裁剪出商家和金额所在区域，再用 ML Kit 中文模型在本机识别。
 *
 * 识别器首次使用时才创建，闲置 [RECOGNIZER_IDLE_RELEASE_MILLIS] 后释放；所有识别都在单线程
 * 后台调度器上串行执行，不占用主线程。位图用完立即回收。
 */
internal class MerchantScreenshotReader(
    private val service: AccessibilityService,
    private val debugStore: MerchantOcrDebugStore,
) {
    private sealed interface ScreenshotAttempt {
        class Taken(val screenshot: AccessibilityService.ScreenshotResult) : ScreenshotAttempt
        class Failed(val errorCode: Int) : ScreenshotAttempt
    }

    private val ocrDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val readerScope = CoroutineScope(SupervisorJob() + ocrDispatcher)
    private val recognizerLock = Mutex()

    @Volatile
    private var recognizer: TextRecognizer? = null
    private var idleRelease: Job? = null

    suspend fun read(request: MerchantOcrRequest): MerchantOcrResult = withContext(ocrDispatcher) {
        recognizerLock.withLock {
            idleRelease?.cancel()
            val startedAtMillis = SystemClock.elapsedRealtime()
            val result = try {
                readLocked(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                MerchantOcrResult(null, "识别异常：${failure::class.java.simpleName} ${failure.message.orEmpty()}")
            } finally {
                scheduleIdleRelease()
            }
            val elapsedMillis = SystemClock.elapsedRealtime() - startedAtMillis
            result.copy(summary = "${result.summary}，耗时 ${elapsedMillis}ms")
        }
    }.also { result -> debugLog(request, "结果：${result.summary}") }

    fun close() {
        readerScope.cancel()
        recognizer?.close()
        recognizer = null
    }

    private suspend fun readLocked(request: MerchantOcrRequest): MerchantOcrResult {
        val screen = when (val attempt = captureScreen()) {
            is ScreenshotAttempt.Failed ->
                return MerchantOcrResult(null, "截图失败：${describeScreenshotError(attempt.errorCode)}")
            is ScreenshotAttempt.Taken -> toSoftwareBitmap(attempt.screenshot)
                ?: return MerchantOcrResult(null, "截图失败：无法读取截图像素")
        }
        val fileStem = debugStore.fileStem(request.observedAtMillis, request.amountCents)
        val crop = request.settings.cropBounds(screen.width, screen.height)
        val cropped = try {
            if (request.settings.debugEnabled) {
                withContext(Dispatchers.IO) { debugStore.saveImage(fileStem, "full", screen) }
            }
            Bitmap.createBitmap(screen, crop.left, crop.top, crop.right - crop.left, crop.bottom - crop.top)
        } catch (failure: RuntimeException) {
            screen.recycle()
            throw failure
        }
        // 裁剪区等于整图时 createBitmap 会直接返回原对象，这时不能提前回收。
        if (cropped !== screen) {
            screen.recycle()
        }
        debugLog(
            request,
            "截图 ${screen.width}x${screen.height}，裁剪 [${crop.left},${crop.top}][${crop.right},${crop.bottom}]，" +
                "预期金额=${request.amountCents}分",
        )

        try {
            if (request.settings.debugEnabled) {
                withContext(Dispatchers.IO) { debugStore.saveImage(fileStem, "crop", cropped) }
            }
            if (looksBlank(cropped)) {
                return MerchantOcrResult(null, "截图为黑屏，可能是安全窗口")
            }
            val lines = recognize(cropped)
            debugLog(
                request,
                "OCR 原始文本 ${lines.size} 行：" + lines.joinToString(" / ") { line ->
                    "${line.text}@${line.left},${line.top},${line.right},${line.bottom}"
                },
            )
            return when (val extraction = WechatOcrMerchantExtractor.extract(lines, request.amountCents)) {
                is OcrMerchantExtraction.Found -> MerchantOcrResult(
                    merchant = extraction.merchant,
                    summary = "识别到商家=${extraction.merchant}" +
                        if (extraction.matchedByAnchor) "（锚点）" else "（金额上方）",
                )
                is OcrMerchantExtraction.AmountMismatch -> MerchantOcrResult(
                    merchant = null,
                    summary = "金额不一致：OCR=${extraction.recognizedAmountsCents}分，无障碍=${request.amountCents}分",
                )
                OcrMerchantExtraction.AmountNotFound -> MerchantOcrResult(null, "未识别到金额，无法交叉校验")
                OcrMerchantExtraction.MerchantNotFound -> MerchantOcrResult(null, "金额已校验，但未找到商家")
            }
        } finally {
            cropped.recycle()
        }
    }

    private suspend fun captureScreen(): ScreenshotAttempt {
        val attempt = requestScreenshot()
        // 系统限制两次截图的最小间隔；这次截图并没有成功，补一次不算重复截图。
        if (attempt is ScreenshotAttempt.Failed &&
            attempt.errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
        ) {
            delay(SCREENSHOT_RETRY_DELAY_MILLIS)
            return requestScreenshot()
        }
        return attempt
    }

    private suspend fun requestScreenshot(): ScreenshotAttempt = suspendCancellableCoroutine { continuation ->
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    if (continuation.isActive) {
                        continuation.resume(ScreenshotAttempt.Taken(screenshot))
                    } else {
                        screenshot.hardwareBuffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) {
                        continuation.resume(ScreenshotAttempt.Failed(errorCode))
                    }
                }
            },
        )
    }

    private fun toSoftwareBitmap(screenshot: AccessibilityService.ScreenshotResult): Bitmap? {
        val hardwareBuffer = screenshot.hardwareBuffer
        return try {
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                ?: return null
            try {
                // 硬件位图不能读像素也不能直接裁剪，先复制成普通位图。
                hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                hardwareBitmap.recycle()
            }
        } finally {
            hardwareBuffer.close()
        }
    }

    /** 安全窗口截出来是纯黑。深色模式背景也不是纯黑，且有白字，不会误判。 */
    private fun looksBlank(bitmap: Bitmap): Boolean {
        for (row in 0 until BLANK_SAMPLE_GRID) {
            for (column in 0 until BLANK_SAMPLE_GRID) {
                val x = (bitmap.width - 1) * column / (BLANK_SAMPLE_GRID - 1)
                val y = (bitmap.height - 1) * row / (BLANK_SAMPLE_GRID - 1)
                val pixel = bitmap.getPixel(x, y)
                val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                if (luminance > BLANK_LUMINANCE_THRESHOLD) {
                    return false
                }
            }
        }
        return true
    }

    private suspend fun recognize(bitmap: Bitmap): List<OcrTextLine> {
        val client = recognizer
            ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                .also { created -> recognizer = created }
        val text = client.process(InputImage.fromBitmap(bitmap, 0)).await()
        return text.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrTextLine(line.text, box.left, box.top, box.right, box.bottom)
        }
    }

    private fun scheduleIdleRelease() {
        idleRelease?.cancel()
        idleRelease = readerScope.launch {
            delay(RECOGNIZER_IDLE_RELEASE_MILLIS)
            recognizerLock.withLock {
                recognizer?.close()
                recognizer = null
            }
        }
    }

    private suspend fun debugLog(request: MerchantOcrRequest, message: String) {
        if (!request.settings.debugEnabled) {
            return
        }
        withContext(Dispatchers.IO) {
            debugStore.appendLog(request.sourceEventId, message)
        }
    }

    private fun describeScreenshotError(errorCode: Int): String {
        val name = when (errorCode) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "系统内部错误"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                "无截图权限（覆盖安装后请在无障碍设置里关闭再打开本服务）"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截图间隔过短"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "无效的显示屏"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "无效的窗口"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "安全窗口禁止截图"
            else -> "未知错误"
        }
        return "$name（code=$errorCode）"
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value -> continuation.resume(value) }
        addOnFailureListener { failure -> continuation.resumeWithException(failure) }
        addOnCanceledListener { continuation.cancel() }
    }

    companion object {
        private const val SCREENSHOT_RETRY_DELAY_MILLIS = 400L
        private const val RECOGNIZER_IDLE_RELEASE_MILLIS = 60_000L
        private const val BLANK_SAMPLE_GRID = 24
        private const val BLANK_LUMINANCE_THRESHOLD = 8
    }
}
