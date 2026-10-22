package com.yanhuozhang.offline.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotImagePlannerTest {
    @Test
    fun `formats are detected from file headers`() {
        assertEquals(ScreenshotImageFormat.JPEG, ScreenshotImageFormat.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(
            ScreenshotImageFormat.PNG,
            ScreenshotImageFormat.detect(bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A)),
        )
        assertEquals(
            ScreenshotImageFormat.WEBP,
            ScreenshotImageFormat.detect("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)),
        )
        assertEquals(ScreenshotImageFormat.GIF, ScreenshotImageFormat.detect("GIF89a".toByteArray()))
        assertEquals(ScreenshotImageFormat.UNKNOWN, ScreenshotImageFormat.detect(bytes(0x00, 0x01)))
        assertEquals(ScreenshotImageFormat.UNKNOWN, ScreenshotImageFormat.detect(ByteArray(0)))
    }

    @Test
    fun `small jpeg screenshot is sent untouched`() {
        val plan = plan(ScreenshotImageFormat.JPEG, width = 900, height = 1800, byteSize = 150_000)

        assertEquals(ScreenshotImagePlan.SendOriginal, plan)
    }

    @Test
    fun `screenshot slightly above the target area is scaled once to the target area`() {
        val plan = plan(ScreenshotImageFormat.JPEG, width = 920, height = 2000, byteSize = 145_000)

        assertTrue(plan is ScreenshotImagePlan.Reencode)
        val reencode = plan as ScreenshotImagePlan.Reencode
        assertEquals(881, reencode.width)
        assertEquals(1916, reencode.height)
        assertTrue(reencode.width.toLong() * reencode.height <= ScreenshotImagePlanner.TARGET_PIXELS)
    }

    @Test
    fun `large png within the pixel budget is only re-encoded`() {
        val plan = plan(
            ScreenshotImageFormat.PNG,
            width = 1000,
            height = 1600,
            byteSize = ScreenshotImagePlanner.MAX_ORIGINAL_BYTES + 1,
        )

        assertEquals(ScreenshotImagePlan.Reencode(1000, 1600), plan)
    }

    @Test
    fun `gif unknown formats location and rotation force re-encoding`() {
        assertEquals(ScreenshotImagePlan.Reencode(800, 600), plan(ScreenshotImageFormat.GIF, 800, 600, 1_000))
        assertEquals(ScreenshotImagePlan.Reencode(800, 600), plan(ScreenshotImageFormat.UNKNOWN, 800, 600, 1_000))
        assertEquals(
            ScreenshotImagePlan.Reencode(800, 600),
            plan(ScreenshotImageFormat.JPEG, 800, 600, 1_000, hasLocation = true),
        )
        assertEquals(
            ScreenshotImagePlan.Reencode(800, 600),
            plan(ScreenshotImageFormat.JPEG, 800, 600, 1_000, needsRotation = true),
        )
    }

    @Test
    fun `long screenshot keeps its aspect ratio`() {
        val plan = plan(ScreenshotImageFormat.PNG, width = 1080, height = 6000, byteSize = 900_000)
            as ScreenshotImagePlan.Reencode

        assertEquals(1080.0 / 6000.0, plan.width.toDouble() / plan.height, 0.002)
        assertTrue(plan.width.toLong() * plan.height <= ScreenshotImagePlanner.TARGET_PIXELS)
    }

    @Test
    fun `sample size stays at or above the target size`() {
        assertEquals(1, ScreenshotImagePlanner.sampleSize(920, 2000, 881, 1916))
        assertEquals(2, ScreenshotImagePlanner.sampleSize(4000, 3000, 1500, 1125))
        assertEquals(4, ScreenshotImagePlanner.sampleSize(8000, 6000, 1500, 1125))
    }

    private fun plan(
        format: ScreenshotImageFormat,
        width: Int,
        height: Int,
        byteSize: Int,
        hasLocation: Boolean = false,
        needsRotation: Boolean = false,
    ): ScreenshotImagePlan {
        return ScreenshotImagePlanner.plan(format, width, height, byteSize, hasLocation, needsRotation)
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { index -> values[index].toByte() }
}
