package com.yanhuozhang.offline.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

fun interface ScreenshotImageEncoder {
    /** 图片无法读取时抛出带中文说明的 [IllegalArgumentException]。 */
    fun encode(bytes: ByteArray): EncodedScreenshot
}

/** 按 [ScreenshotImagePlanner] 的决定原样发送或重新编码；所有位图用完立即回收。 */
class BitmapScreenshotImageEncoder : ScreenshotImageEncoder {
    private class Metadata(val hasLocation: Boolean, val rotationDegrees: Int)

    override fun encode(bytes: ByteArray): EncodedScreenshot {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片，请换一张截图" }

        val format = ScreenshotImageFormat.detect(bytes)
        val metadata = readMetadata(bytes)
        val plan = ScreenshotImagePlanner.plan(
            format = format,
            width = bounds.outWidth,
            height = bounds.outHeight,
            byteSize = bytes.size,
            hasLocation = metadata.hasLocation,
            needsRotation = metadata.rotationDegrees != 0,
        )
        return when (plan) {
            ScreenshotImagePlan.SendOriginal ->
                EncodedScreenshot(requireNotNull(format.mimeType), bytes)
            is ScreenshotImagePlan.Reencode ->
                EncodedScreenshot("image/jpeg", reencode(bytes, bounds, plan, metadata.rotationDegrees))
        }
    }

    private fun reencode(
        bytes: ByteArray,
        bounds: BitmapFactory.Options,
        plan: ScreenshotImagePlan.Reencode,
        rotationDegrees: Int,
    ): ByteArray {
        val options = BitmapFactory.Options().apply {
            inSampleSize = ScreenshotImagePlanner.sampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                targetWidth = plan.width,
                targetHeight = plan.height,
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("无法读取这张图片，请换一张截图")
        try {
            if (bitmap.width != plan.width || bitmap.height != plan.height) {
                bitmap = bitmap.replaceWith(Bitmap.createScaledBitmap(bitmap, plan.width, plan.height, true))
            }
            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                bitmap = bitmap.replaceWith(
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true),
                )
            }
            if (bitmap.hasAlpha()) {
                // JPEG 没有透明通道，透明像素会变黑；先铺白底。
                val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                Canvas(opaque).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
                bitmap = bitmap.replaceWith(opaque)
            }
            return ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun Bitmap.replaceWith(next: Bitmap): Bitmap {
        if (next !== this) {
            recycle()
        }
        return next
    }

    private fun readMetadata(bytes: ByteArray): Metadata {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            val rotation = when (
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            Metadata(
                hasLocation = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null,
                rotationDegrees = rotation,
            )
        } catch (_: Exception) {
            Metadata(hasLocation = false, rotationDegrees = 0)
        }
    }

    private companion object {
        const val JPEG_QUALITY = 92
    }
}
