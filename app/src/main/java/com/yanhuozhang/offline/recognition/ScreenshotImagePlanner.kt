package com.yanhuozhang.offline.recognition

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

internal enum class ScreenshotImageFormat(val mimeType: String?) {
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),
    GIF("image/gif"),
    UNKNOWN(null),
    ;

    companion object {
        /** 按文件头判断格式；DeepSeek 也按内容而不是扩展名识别。 */
        fun detect(bytes: ByteArray): ScreenshotImageFormat {
            fun at(index: Int): Int = if (index < bytes.size) bytes[index].toInt() and 0xFF else -1
            fun ascii(start: Int, text: String): Boolean =
                text.indices.all { offset -> at(start + offset) == text[offset].code }
            return when {
                at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> JPEG
                at(0) == 0x89 && ascii(1, "PNG") -> PNG
                ascii(0, "RIFF") && ascii(8, "WEBP") -> WEBP
                ascii(0, "GIF8") -> GIF
                else -> UNKNOWN
            }
        }
    }
}

internal sealed interface ScreenshotImagePlan {
    /** 原样发送原始字节，不重新编码，避免再压一遍产生噪点。 */
    data object SendOriginal : ScreenshotImagePlan

    /** 解码后按给定尺寸重新编码为 JPEG；尺寸与原图相同时只是换编码。 */
    data class Reencode(val width: Int, val height: Int) : ScreenshotImagePlan
}

/**
 * 决定截图怎么发给 DeepSeek。
 *
 * DeepSeek 会把总像素超过约 1300×1300 的图片等比缩小到这个面积（不裁剪）。超出时在本机一次性
 * 缩到同样面积，服务端就不会再缩第二次；没超出时尽量原样发送。
 */
internal object ScreenshotImagePlanner {
    const val TARGET_PIXELS = 1300L * 1300L
    const val MAX_ORIGINAL_BYTES = 2 * 1024 * 1024

    fun plan(
        format: ScreenshotImageFormat,
        width: Int,
        height: Int,
        byteSize: Int,
        hasLocation: Boolean,
        needsRotation: Boolean,
    ): ScreenshotImagePlan {
        require(width > 0 && height > 0) { "图片尺寸无效" }
        val pixels = width.toLong() * height
        if (pixels > TARGET_PIXELS) {
            val scale = sqrt(TARGET_PIXELS.toDouble() / pixels)
            return ScreenshotImagePlan.Reencode(
                width = max(1, floor(width * scale).toInt()),
                height = max(1, floor(height * scale).toInt()),
            )
        }
        val canSendOriginal = format == ScreenshotImageFormat.JPEG ||
            format == ScreenshotImageFormat.PNG ||
            format == ScreenshotImageFormat.WEBP
        // 相机拍的纸质小票可能带 GPS 位置或旋转标记：重新编码会去掉位置，并把图片转正。
        if (!canSendOriginal || byteSize > MAX_ORIGINAL_BYTES || hasLocation || needsRotation) {
            return ScreenshotImagePlan.Reencode(width, height)
        }
        return ScreenshotImagePlan.SendOriginal
    }

    /** 解码时先用 2 的幂降采样省内存，但不低于目标尺寸，剩下的再精确缩放。 */
    fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }
}
