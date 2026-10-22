package com.yanhuozhang.offline.recognition

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val MAX_SCREENSHOT_BYTES = 30 * 1024 * 1024

/** 读取整张图片；超过 [MAX_SCREENSHOT_BYTES] 时抛出带中文说明的异常，避免把超大文件读进内存。 */
internal fun readScreenshotBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
        require(output.size() <= MAX_SCREENSHOT_BYTES) { "图片超过 30MB，无法识别" }
    }
    require(output.size() > 0) { "图片内容为空" }
    return output.toByteArray()
}

/**
 * 从系统分享收到、还没识别的截图。
 *
 * 分享方只给接收页面临时授权读取，所以收到后立即复制到应用缓存；主界面解锁后再取走识别。
 * 截图文件只在进程内传递，不经过 Intent，其他 App 无法借这个入口让本应用读取任意文件。
 */
class SharedScreenshotInbox(private val directory: File) {
    private val pending = MutableStateFlow<File?>(null)
    val pendingScreenshot: StateFlow<File?> = pending.asStateFlow()

    fun store(input: InputStream): File {
        val bytes = readScreenshotBytes(input)
        directory.mkdirs()
        // 只保留最新一张：旧文件要么已经识别完，要么已被这次分享替代。
        directory.listFiles()?.forEach { file -> file.delete() }
        val file = File(directory, "shared-${System.currentTimeMillis()}.img")
        file.writeBytes(bytes)
        return file
    }

    fun offer(file: File) {
        pending.value = file
    }

    /** 只有仍在等待的那张才能取走，避免界面重组时重复识别同一张。 */
    fun take(file: File): Boolean = pending.compareAndSet(file, null)
}
