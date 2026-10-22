package com.yanhuozhang.offline.system

import android.app.Application
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.yanhuozhang.offline.core.WechatPaymentPageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@Suppress("DEPRECATION")
class VisiblePaymentNodeCollectorTest {
    @Test
    fun hiddenBranchesCannotUseUpPaymentNodeBudget() {
        val root = node("", Rect(0, 0, 1200, 2608))
        for (index in 0 until 200) {
            val hidden = node("隐藏内容", Rect(0, 0, 0, 0), visible = false)
            shadowOf(root).addChild(hidden)
        }
        var parent = root
        for (depth in 0 until 14) {
            val container = node("", Rect(0, 0, 1200, 2608))
            shadowOf(parent).addChild(container)
            parent = container
        }
        shadowOf(parent).addChild(node("支付成功", Rect(0, 192, 1200, 273)))
        shadowOf(parent).addChild(node("京东", Rect(549, 465, 651, 546)))
        shadowOf(parent).addChild(node("￥9.90", Rect(419, 576, 782, 750)))
        shadowOf(parent).addChild(node("返回商家", Rect(324, 2128, 876, 2272)))

        val unfiltered = AccessibilityNodeCollector.collectDetailed(root, 200)
        assertTrue(unfiltered.truncated)
        assertFalse(WechatPaymentPageParser.containsPaymentSuccessMarker(unfiltered.nodes))

        val visible = AccessibilityNodeCollector.collectDetailed(root, 200, visibleOnly = true)
        val payment = WechatPaymentPageParser.parseNodes(visible.nodes)
        assertFalse(visible.truncated)
        assertEquals(990L, payment?.amountCents)
        assertEquals("京东", payment?.counterparty)
    }

    @Test
    fun hiddenAmountCannotMakeVisiblePaymentAmountAmbiguous() {
        val root = node("", Rect(0, 0, 1200, 2608))
        shadowOf(root).addChild(node("支付成功", Rect(0, 192, 1200, 273)))
        shadowOf(root).addChild(node("京东", Rect(549, 465, 651, 546)))
        shadowOf(root).addChild(node("￥17.62", Rect(419, 576, 782, 750)))
        shadowOf(root).addChild(node("￥9.90", Rect(419, 576, 782, 750), visible = false))
        shadowOf(root).addChild(node("返回商家", Rect(324, 2128, 876, 2272)))

        val visible = AccessibilityNodeCollector.collectDetailed(root, 200, visibleOnly = true)
        val payment = WechatPaymentPageParser.parseNodes(visible.nodes)

        assertEquals(1762L, payment?.amountCents)
        assertEquals("京东", payment?.counterparty)
    }

    private fun node(
        description: String,
        bounds: Rect,
        visible: Boolean = true,
    ): AccessibilityNodeInfo {
        return AccessibilityNodeInfo.obtain().apply {
            contentDescription = description
            isVisibleToUser = visible
            setBoundsInScreen(bounds)
        }
    }
}
