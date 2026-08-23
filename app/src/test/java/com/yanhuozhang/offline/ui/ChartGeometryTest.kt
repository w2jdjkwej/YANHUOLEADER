package com.yanhuozhang.offline.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartGeometryTest {
    @Test
    fun `rectangular canvas produces centered square bounds`() {
        val bounds = centeredSquareBounds(width = 800f, height = 220f)

        assertEquals(290f, bounds.left)
        assertEquals(0f, bounds.top)
        assertEquals(220f, bounds.size)
    }

    @Test
    fun `negative canvas dimensions produce an empty centered square`() {
        val bounds = centeredSquareBounds(width = -10f, height = 100f)

        assertEquals(-5f, bounds.left)
        assertEquals(50f, bounds.top)
        assertEquals(0f, bounds.size)
    }

    @Test
    fun `bar baseline leaves room for round cap`() {
        val geometry = trendBarGeometry(
            canvasHeight = 300f,
            barWidth = 40f,
            bottomPadding = 8f,
            amount = 50,
            maximumAmount = 100,
        )

        assertEquals(272f, geometry.baselineY)
        assertEquals(146f, geometry.topY)
        assertEquals(true, geometry.shouldDraw)
    }

    @Test
    fun `full amount keeps top round cap inside canvas`() {
        val geometry = trendBarGeometry(
            canvasHeight = 300f,
            barWidth = 40f,
            bottomPadding = 8f,
            amount = 100,
            maximumAmount = 100,
        )

        assertEquals(20f, geometry.topY)
        assertEquals(true, geometry.shouldDraw)
    }

    @Test
    fun `zero amount stays at baseline and is not drawn`() {
        val geometry = trendBarGeometry(
            canvasHeight = 300f,
            barWidth = 40f,
            bottomPadding = 8f,
            amount = 0,
            maximumAmount = 100,
        )

        assertEquals(272f, geometry.baselineY)
        assertEquals(272f, geometry.topY)
        assertEquals(false, geometry.shouldDraw)
    }

    @Test
    fun `non positive maximum produces hidden zero height bar`() {
        val geometry = trendBarGeometry(
            canvasHeight = 300f,
            barWidth = 40f,
            bottomPadding = 8f,
            amount = 50,
            maximumAmount = 0,
        )

        assertEquals(272f, geometry.baselineY)
        assertEquals(272f, geometry.topY)
        assertEquals(false, geometry.shouldDraw)
    }
}
