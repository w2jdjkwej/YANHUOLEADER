package com.yanhuozhang.offline.system

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.yanhuozhang.offline.YanHuoApplication
import com.yanhuozhang.offline.core.EventSource
import com.yanhuozhang.offline.core.RawObservation
import com.yanhuozhang.offline.core.ScreenTextNode
import com.yanhuozhang.offline.core.WechatPaymentPageParser
import com.yanhuozhang.offline.core.WechatRedPacketParser
import com.yanhuozhang.offline.data.IngestionOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WechatPaymentAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val captureGate = PaymentPageCaptureGate()
    private val redPacketCoordinator = RedPacketCaptureCoordinator()
    private var pendingRead: Job? = null
    private var pendingMerchantRetry: Job? = null
    private var pendingEventType: Int = 0
    private var pendingWechatEvent = false
    private var merchantReadAttempts = 0
    private var windowReadAttempts = 0
    private var burstStartedAtMillis = 0L
    private var readStarted = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "微信支付页面识别服务已连接")
        scheduleWindowInspection(
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            fromWechatEvent = false,
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != WECHAT_PACKAGE) {
            return
        }
        scheduleWindowInspection(
            eventType = event.eventType,
            fromWechatEvent = true,
        )
    }

    private fun scheduleWindowInspection(eventType: Int, fromWechatEvent: Boolean) {
        pendingEventType = eventType
        pendingWechatEvent = pendingWechatEvent || fromWechatEvent
        val nowMillis = SystemClock.uptimeMillis()
        if (pendingRead?.isActive == true) {
            // 支付成功页是分几帧画出来的，事件会成串到达。把读取推迟到事件停下来之后再做，
            // 否则会读到商家还没渲染出来的半成品页面。但事件流持续不断时不能无限推迟，
            // 超过上限就让已排期的读取照常执行。
            if (readStarted || nowMillis - burstStartedAtMillis >= MAXIMUM_READ_DELAY_MILLIS) {
                return
            }
            pendingRead?.cancel()
        } else {
            burstStartedAtMillis = nowMillis
            windowReadAttempts = 0
        }
        if (fromWechatEvent) {
            Log.d(TAG, "收到微信窗口事件：type=$eventType")
        }
        readStarted = false
        pendingRead = serviceScope.launch {
            delay(READ_DEBOUNCE_MILLIS)
            readStarted = true
            inspectCurrentWindow(pendingEventType, pendingWechatEvent)
        }
    }

    private suspend fun inspectCurrentWindow(eventType: Int, fromWechatEvent: Boolean) {
        val root = resolveWechatRoot()
        if (root == null) {
            if (fromWechatEvent && retryWechatWindowRead(eventType)) {
                return
            }
            markPaymentPageAbsent()
            return
        }

        val collected = AccessibilityNodeCollector.collectDetailed(root, MAXIMUM_NODE_COUNT)
        val textNodes = collected.nodes
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        val screenWidth = rootBounds.width().takeIf { width -> width > 0 }
            ?: resources.displayMetrics.widthPixels
        val observedAtMillis = System.currentTimeMillis()

        val redPacketAmountPage = WechatRedPacketParser.parseAmountPage(textNodes)
        if (redPacketAmountPage != null) {
            val tracked = redPacketCoordinator.observeAmount(
                amountCents = redPacketAmountPage.amountCents,
                nowMillis = observedAtMillis,
            )
            markPaymentPageAbsent()
            Log.d(TAG, "微信红包金额页已读取：tracked=$tracked")
            return
        }

        val redPacketChatPage = WechatRedPacketParser.parseChatPage(textNodes, screenWidth)
        if (redPacketChatPage != null) {
            val redPacketResult = redPacketCoordinator.observeChat(
                page = redPacketChatPage,
                nowMillis = observedAtMillis,
            )
            markPaymentPageAbsent()
            if (redPacketResult is RedPacketCaptureResult.Completed) {
                ingestSentRedPacket(redPacketResult.amountCents, observedAtMillis)
            }
            return
        }

        val paymentPage = WechatPaymentPageParser.parseNodes(textNodes)
        if (paymentPage == null) {
            markPaymentPageAbsent()
            val sampleTexts = textNodes.take(10)
                .joinToString("|") { node -> node.text.trim().take(12) }
            Log.d(TAG, "微信页面未识别为支付成功页，节点数=${textNodes.size}，截断=${collected.truncated}，文本=${sampleTexts}")
            return
        }

        val counterparty = paymentPage.counterparty
        if (counterparty == null && scheduleMerchantRetry(eventType)) {
            Log.d(TAG, "微信支付成功页暂未读取到商家，准备再次读取：第${merchantReadAttempts}次")
            return
        }
        if (counterparty == null) {
            // 商家始终读不到时也要把这笔支出交给摄入流程，由待确认通知提示用户补填，
            // 绝不能静默丢弃整笔消费。
            Log.w(TAG, "微信支付成功页读不到商家，按待确认记账。节点数=${textNodes.size}，截断=${collected.truncated}，节点=${describeNodes(textNodes)}")
        }
        windowReadAttempts = 0
        merchantReadAttempts = 0
        pendingMerchantRetry?.cancel()

        // 签名不含商家名本身，只记录"这一页有没有读到商家"。这样同一页面重复读到同一个商家
        // 不会重复采集，而先前没读到、后来读到了则可以再采集一次，把商家补进已有账单。
        // 离开页面时 markPaymentPageAbsent() 会重置签名。
        val signature = "page|${paymentPage.amountCents}|${counterparty != null}"
        if (!captureGate.shouldCapture(signature)) {
            return
        }
        val sourceEventId = captureGate.eventIdFor(
            amountCents = paymentPage.amountCents,
            observedAtMillis = observedAtMillis,
        )

        val raw = RawObservation(
            source = EventSource.WECHAT_PAYMENT_PAGE,
            sourceEventId = sourceEventId,
            title = counterparty,
            content = buildString {
                appendLine("支付成功")
                appendLine(counterparty.orEmpty())
                append('￥')
                append(formatAmount(paymentPage.amountCents))
            },
            observedAtMillis = observedAtMillis,
        )

        val application = applicationContext as YanHuoApplication
        val outcome = withContext(Dispatchers.IO) {
            application.container.automaticClassificationCoordinator.ingest(raw)
        }
        if (outcome is IngestionOutcome.Pending) {
            application.container.notificationManager.showPending(outcome.eventId, outcome.reason)
        }
        Log.d(TAG, "微信支付成功页已处理：${outcome::class.java.simpleName}")
    }

    private suspend fun ingestSentRedPacket(amountCents: Long, observedAtMillis: Long) {
        val raw = RawObservation(
            source = EventSource.WECHAT_PAYMENT_PAGE,
            sourceEventId = "red-packet-$observedAtMillis-$amountCents",
            title = null,
            content = buildString {
                appendLine("微信红包已发送")
                append('￥')
                append(formatAmount(amountCents))
            },
            observedAtMillis = observedAtMillis,
        )
        val application = applicationContext as YanHuoApplication
        val outcome = withContext(Dispatchers.IO) {
            application.container.automaticClassificationCoordinator.ingest(raw)
        }
        if (outcome is IngestionOutcome.Pending) {
            application.container.notificationManager.showPending(outcome.eventId, outcome.reason)
        }
        Log.d(TAG, "微信红包发送已处理：${outcome::class.java.simpleName}")
    }

    private fun markPaymentPageAbsent() {
        captureGate.markPageAbsent()
        pendingWechatEvent = false
        merchantReadAttempts = 0
        windowReadAttempts = 0
        pendingMerchantRetry?.cancel()
    }

    /** 安排一次重读，返回 true 表示已排期、本轮应当等待重读结果。 */
    private fun scheduleMerchantRetry(eventType: Int): Boolean {
        if (pendingMerchantRetry?.isActive == true) {
            return true
        }
        if (merchantReadAttempts >= MAXIMUM_MERCHANT_READ_ATTEMPTS) {
            return false
        }
        merchantReadAttempts += 1
        pendingMerchantRetry = serviceScope.launch {
            delay(MERCHANT_RETRY_DELAY_MILLIS)
            // 当前重试已经开始执行。先清空引用，后续仍缺商家时才能安排下一次重试，
            // 读到商家时也不会在摄入前取消正在执行的这个协程。
            pendingMerchantRetry = null
            inspectCurrentWindow(eventType, fromWechatEvent = true)
        }
        return true
    }

    private fun describeNodes(nodes: List<ScreenTextNode>): String {
        return nodes.take(MAXIMUM_LOGGED_NODE_COUNT).joinToString(separator = " / ") { node ->
            "${node.text.take(16)}@${node.left},${node.top},${node.right},${node.bottom}"
        }
    }

    /**
     * 支付成功页通常不持有输入焦点，rootInActiveWindow 会指向别的窗口，
     * 只认它会让整条识别链路一步都走不下去。这里先取活动窗口，不是微信就借助
     * flagRetrieveInteractiveWindows 遍历全部窗口去找微信自己的那个；
     * 都找不到时退回活动窗口本身（本轮改动之前的行为），保证不会比以前更差。
     */
    private fun resolveWechatRoot(): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot?.packageName?.toString() == WECHAT_PACKAGE) {
            return activeRoot
        }

        val allWindows = windows.orEmpty()
        val wechatRoot = allWindows
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { window ->
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION
                }.thenByDescending { window -> window.layer },
            )
            .firstNotNullOfOrNull { window ->
                window.root?.takeIf { root -> root.packageName?.toString() == WECHAT_PACKAGE }
            }
        if (wechatRoot != null) {
            Log.d(TAG, "活动窗口非微信（pkg=${activeRoot?.packageName}），已从 ${allWindows.size} 个窗口中取到微信窗口")
            return wechatRoot
        }

        Log.d(
            TAG,
            "未找到微信窗口：活动窗口 pkg=${activeRoot?.packageName}，" +
                "全部窗口=${allWindows.joinToString { window -> "${window.type}:${window.root?.packageName}" }}",
        )
        // 包名为空时无从判断，仍按微信窗口尝试解析（本轮改动之前的行为）；
        // 但明确属于别的应用时不再解析，避免把其它应用的"支付成功"页误当微信账单。
        return activeRoot?.takeIf { root -> root.packageName == null }
    }

    private fun retryWechatWindowRead(eventType: Int): Boolean {
        if (windowReadAttempts >= MAXIMUM_WINDOW_READ_ATTEMPTS) {
            Log.d(TAG, "微信窗口未就绪，已停止重试")
            return false
        }
        windowReadAttempts += 1
        Log.d(TAG, "微信窗口尚未就绪，准备重试：第${windowReadAttempts}次")
        pendingRead = serviceScope.launch {
            delay(WINDOW_RETRY_DELAY_MILLIS)
            inspectCurrentWindow(eventType, fromWechatEvent = true)
        }
        return true
    }

    private fun formatAmount(amountCents: Long): String {
        val yuan = amountCents / 100
        val fen = (amountCents % 100).toString().padStart(2, '0')
        return "$yuan.$fen"
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        pendingRead?.cancel()
        pendingMerchantRetry?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "YanHuoPaymentPage"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val READ_DEBOUNCE_MILLIS = 250L
        private const val MAXIMUM_READ_DELAY_MILLIS = 900L
        private const val WINDOW_RETRY_DELAY_MILLIS = 250L
        private const val MAXIMUM_WINDOW_READ_ATTEMPTS = 5
        private const val MERCHANT_RETRY_DELAY_MILLIS = 500L
        private const val MAXIMUM_MERCHANT_READ_ATTEMPTS = 3
        private const val MAXIMUM_NODE_COUNT = 200
        private const val MAXIMUM_LOGGED_NODE_COUNT = 30
    }
}
