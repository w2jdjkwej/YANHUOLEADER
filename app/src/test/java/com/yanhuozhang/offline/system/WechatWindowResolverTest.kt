package com.yanhuozhang.offline.system

import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@Suppress("DEPRECATION")
class WechatWindowResolverTest {
    @Test
    fun paymentEventWindowWinsOverActiveWechatChatWindow() {
        val chat = root("com.tencent.mm")
        val payment = root("com.tencent.mm").apply { contentDescription = "目标支付窗口" }
        val windows = listOf(window(1, chat), window(2, payment))

        assertEquals("目标支付窗口", WechatWindowResolver.findRoot(chat, windows, 2)?.contentDescription?.toString())
    }

    @Test
    fun eventWindowFromAnotherAppCannotBeReadAsWechatPayment() {
        val chat = root("com.tencent.mm")
        val otherApp = root("com.jingdong.app.mall")

        assertSame(chat, WechatWindowResolver.findRoot(chat, listOf(window(2, otherApp)), 2))
    }

    @Test
    fun applicationWindowWinsOverHigherSystemWindowWhenFocusIsElsewhere() {
        val payment = root("com.tencent.mm").apply { contentDescription = "目标支付窗口" }
        val popup = root("com.tencent.mm")
        val windows = listOf(
            window(1, popup, layer = 10, type = AccessibilityWindowInfo.TYPE_SYSTEM),
            window(2, payment, layer = 1),
        )

        assertEquals("目标支付窗口", WechatWindowResolver.findRoot(root("com.android.systemui"), windows, -1)?.contentDescription?.toString())
    }

    @Test
    fun highestWechatApplicationWindowIsFallbackWhenEventWindowDisappears() {
        val oldWindow = root("com.tencent.mm")
        val payment = root("com.tencent.mm").apply { contentDescription = "目标支付窗口" }
        val windows = listOf(window(1, oldWindow, layer = 1), window(2, payment, layer = 2))

        assertEquals("目标支付窗口", WechatWindowResolver.findRoot(null, windows, 3)?.contentDescription?.toString())
    }

    @Test
    fun unrelatedApplicationCannotProduceWechatPaymentRoot() {
        val otherApp = root("com.jingdong.app.mall")

        assertNull(WechatWindowResolver.findRoot(otherApp, listOf(window(1, otherApp)), 1))
    }

    private fun root(packageName: String): AccessibilityNodeInfo {
        return AccessibilityNodeInfo.obtain().apply { this.packageName = packageName }
    }

    private fun window(
        id: Int,
        root: AccessibilityNodeInfo,
        layer: Int = 1,
        type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
    ): AccessibilityWindowInfo {
        val window = AccessibilityWindowInfo.obtain()
        val shadow = shadowOf(window)
        shadow.setRoot(root)
        shadow.setId(id)
        shadow.setLayer(layer)
        shadow.setType(type)
        return window
    }
}
