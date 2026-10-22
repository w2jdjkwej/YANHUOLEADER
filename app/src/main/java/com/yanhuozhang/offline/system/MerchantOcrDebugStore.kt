package com.yanhuozhang.offline.system

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 截图识别商家的调试输出，只在设置里开启调试模式时写入。
 *
 * 放在应用专属外部目录（Android/data/<包名>/files/merchant-ocr），release 包也能用
 * `adb pull` 取出，又不需要任何存储权限。所有方法都会读写文件，调用方需在 IO 线程调用。
 */
class MerchantOcrDebugStore(private val context: Context) {
    val directory: File
        get() = context.getExternalFilesDir(DIRECTORY_NAME) ?: File(context.filesDir, DIRECTORY_NAME)

    fun saveImage(fileStem: String, suffix: String, bitmap: Bitmap) {
        val target = File(ensureDirectory(), "${fileStem}_$suffix.png")
        try {
            target.outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            pruneImages()
        } catch (failure: IOException) {
            Log.w(TAG, "保存调试截图失败", failure)
        }
    }

    @Synchronized
    fun appendLog(sourceEventId: String, message: String) {
        try {
            val logFile = File(ensureDirectory(), LOG_FILE_NAME)
            if (logFile.length() > MAXIMUM_LOG_BYTES) {
                val rotated = File(logFile.parentFile, ROTATED_LOG_FILE_NAME)
                rotated.delete()
                logFile.renameTo(rotated)
            }
            logFile.appendText("${timestamp(System.currentTimeMillis())} [$sourceEventId] $message\n")
        } catch (failure: IOException) {
            Log.w(TAG, "写入 OCR 调试日志失败", failure)
        }
    }

    /** 删除全部调试截图和日志，返回删除的文件数。 */
    @Synchronized
    fun clear(): Int {
        val files = directory.listFiles().orEmpty()
        return files.count { file -> file.isFile && file.delete() }
    }

    fun fileStem(observedAtMillis: Long, amountCents: Long): String {
        return "${FILE_TIME_FORMATTER.format(Instant.ofEpochMilli(observedAtMillis))}_$amountCents"
    }

    private fun pruneImages() {
        val images = directory.listFiles { file -> file.isFile && file.name.endsWith(".png") }
            .orEmpty()
            .sortedByDescending { file -> file.name }
        for (image in images.drop(MAXIMUM_IMAGE_FILES)) {
            image.delete()
        }
    }

    private fun ensureDirectory(): File {
        return directory.also { folder -> folder.mkdirs() }
    }

    private fun timestamp(millis: Long): String {
        return LOG_TIME_FORMATTER.format(Instant.ofEpochMilli(millis))
    }

    companion object {
        private const val TAG = "YanHuoMerchantOcr"
        private const val DIRECTORY_NAME = "merchant-ocr"
        private const val LOG_FILE_NAME = "ocr-log.txt"
        private const val ROTATED_LOG_FILE_NAME = "ocr-log.1.txt"
        private const val MAXIMUM_LOG_BYTES = 2L * 1024 * 1024

        // 每次截图保存裁剪前后两张，保留最近 40 组。
        private const val MAXIMUM_IMAGE_FILES = 80
        private val FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault())
        private val LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
    }
}
