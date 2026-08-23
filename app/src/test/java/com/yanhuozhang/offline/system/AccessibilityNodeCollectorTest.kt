package com.yanhuozhang.offline.system

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.yanhuozhang.offline.core.ScreenBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Suppress("DEPRECATION")
class AccessibilityNodeCollectorTest {
    @Test
    fun `collects text description and nearest clickable container`() {
        val rootInfo = AccessibilityNodeInfo.obtain().apply {
            setBoundsInScreen(Rect(0, 0, 1_200, 2_608))
        }
        val clickableCard = AccessibilityNodeInfo.obtain().apply {
            isClickable = true
            setBoundsInScreen(Rect(276, 1_572, 1_026, 1_832))
        }
        val label = AccessibilityNodeInfo.obtain().apply {
            text = "金额"
            contentDescription = "金额0.01¥"
            setBoundsInScreen(Rect(306, 1_612, 806, 1_712))
        }
        shadowOf(rootInfo).addChild(clickableCard)
        shadowOf(clickableCard).addChild(label)

        val nodes = AccessibilityNodeCollector.collect(
            root = rootInfo,
            maximumNodes = 200,
        )

        assertTrue(nodes.any { node -> node.text == "金额" })
        assertTrue(nodes.any { node -> node.text == "金额0.01¥" })
        val amountNode = nodes.first { node -> node.text == "金额0.01¥" }
        assertEquals(ScreenBounds(276, 1_572, 1_026, 1_832), amountNode.clickableContainer)
    }
}
