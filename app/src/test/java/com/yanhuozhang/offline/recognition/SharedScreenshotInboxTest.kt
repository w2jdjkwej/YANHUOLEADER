package com.yanhuozhang.offline.recognition

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedScreenshotInboxTest {
    @Test
    fun `stored screenshot replaces older files and can be taken once`() {
        val directory = Files.createTempDirectory("shared-screenshots").toFile()
        try {
            val inbox = SharedScreenshotInbox(directory)
            val first = inbox.store(ByteArrayInputStream(byteArrayOf(1)))
            Thread.sleep(2)
            val second = inbox.store(ByteArrayInputStream(byteArrayOf(2, 3)))

            assertFalse(first.exists())
            assertArrayEquals(byteArrayOf(2, 3), second.readBytes())
            assertEquals(1, directory.listFiles()?.size)

            inbox.offer(second)
            assertFalse(inbox.take(first))
            assertTrue(inbox.take(second))
            assertFalse(inbox.take(second))
            assertNull(inbox.pendingScreenshot.value)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `empty and oversized images are rejected`() {
        val empty = runCatching { readScreenshotBytes(ByteArrayInputStream(ByteArray(0))) }
        val oversized = runCatching { readScreenshotBytes(endlessStream()) }

        assertTrue(empty.exceptionOrNull() is IllegalArgumentException)
        assertTrue(oversized.exceptionOrNull()?.message.orEmpty().contains("30MB"))
    }

    private fun endlessStream(): InputStream = object : InputStream() {
        override fun read(): Int = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = length
    }
}
