package com.yanhuozhang.offline.system

import android.accessibilityservice.AccessibilityService
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.io.FileDescriptor
import java.io.PrintWriter
import com.yanhuozhang.offline.YanHuoApplication
import com.yanhuozhang.offline.core.EventSource
import com.yanhuozhang.offline.core.PaymentPageObservation
import com.yanhuozhang.offline.core.RawObservation
import com.yanhuozhang.offline.core.ScreenTextNode
import com.yanhuozhang.offline.core.WechatPaymentPageParser
import com.yanhuozhang.offline.core.WechatRedPacketParser
import com.yanhuozhang.offline.data.IngestionOutcome
import com.yanhuozhang.offline.settings.MerchantOcrSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class WechatPaymentAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowReadDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val diagnosticHistory = ArrayDeque<String>()
    private var lastPaymentDiagnostic = "尚未读取到支付成功页"
    private val captureGate = PaymentPageCaptureGate()
    private val redPacketCoordinator = RedPacketCaptureCoordinator()
    private var pendingRead: Job? = null
    private var pendingMerchantRetry: Job? = null
    private var pendingEventType: Int = 0
    private var pendingEventWindowId = -1
    private var lastEventAtMillis = 0L
    private var nextReadRequested = false
    private var pendingWechatEvent = false
    private var merchantReadAttempts = 0
    private var windowReadAttempts = 0
    private var burstStartedAtMillis = 0L
    private var readStarted = false
    private val merchantOcrSessions = MerchantOcrSessions()
    private val merchantOcrDebugStore by lazy { MerchantOcrDebugStore(applicationContext) }
    private var merchantScreenshotReader: MerchantScreenshotReader? = null
    private var merchantOcrSettings = MerchantOcrSettings()
    private var settingsCollection: Job? = null
    private var nodeMerchantEventId: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val application = applicationContext as YanHuoApplication
        enterForeground(application)
        settingsCollection?.cancel()
        settingsCollection = serviceScope.launch {
            application.container.settingsRepository.settings.collect { settings ->
                merchantOcrSettings = settings.merchantOcr
            }
        }
        recordDiagnostic("微信支付页面识别服务已连接")
        scheduleWindowInspection(
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            fromWechatEvent = false,
        )
    }

    /**
     * HyperOS 的冻结机制会在微信启动时冻结后台应用（包括本服务所在进程），无障碍事件和通知要等
     * 解冻后才送达，那时支付成功页早已关闭。前台服务的进程不会被冻结。
     * 进入失败时只记录诊断，识别服务照常运行。
     */
    private fun enterForeground(application: YanHuoApplication) {
        try {
            startForeground(
                SystemNotificationManager.PAYMENT_READER_NOTIFICATION_ID,
                application.container.notificationManager.paymentReaderNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            recordDiagnostic("已转为前台服务，避免后台冻结")
        } catch (failure: RuntimeException) {
            recordDiagnostic("转为前台服务失败：${failure::class.java.simpleName} ${failure.message.orEmpty()}")
            Log.w(TAG, "无法转为前台服务", failure)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            return
        }
        val fromWechatEvent = event.packageName?.toString() == WECHAT_PACKAGE
        if (!fromWechatEvent && event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return
        }
        scheduleWindowInspection(
            eventType = event.eventType,
            fromWechatEvent = fromWechatEvent,
            eventWindowId = event.windowId,
        )
    }

    private fun scheduleWindowInspection(
        eventType: Int,
        fromWechatEvent: Boolean,
        eventWindowId: Int = -1,
    ) {
        pendingEventType = eventType
        if (fromWechatEvent || !pendingWechatEvent) {
            pendingEventWindowId = eventWindowId
        }
        pendingWechatEvent = pendingWechatEvent || fromWechatEvent
        lastEventAtMillis = SystemClock.uptimeMillis()
        if (pendingRead?.isActive == true) {
            if (readStarted) {
                nextReadRequested = true
            }
            return
        }

        burstStartedAtMillis = lastEventAtMillis
        windowReadAttempts = 0
        readStarted = false
        pendingRead = serviceScope.launch {
            try {
                // 连续事件共用一个任务，最多等待 900ms。
                while (true) {
                    val readAtMillis = minOf(
                        lastEventAtMillis + READ_DEBOUNCE_MILLIS,
                        burstStartedAtMillis + MAXIMUM_READ_DELAY_MILLIS,
                    )
                    val remainingMillis = readAtMillis - SystemClock.uptimeMillis()
                    if (remainingMillis <= 0) {
                        break
                    }
                    delay(remainingMillis)
                }
                readStarted = true
                inspectCurrentWindow(pendingEventType, pendingWechatEvent)
            } finally {
                pendingRead = null
                readStarted = false
                if (nextReadRequested) {
                    nextReadRequested = false
                    scheduleWindowInspection(
                        pendingEventType,
                        pendingWechatEvent,
                        pendingEventWindowId,
                    )
                }
            }
        }
    }

    private suspend fun inspectCurrentWindow(eventType: Int, fromWechatEvent: Boolean) {
        var snapshot = readWechatWindow()
        while (snapshot == null && fromWechatEvent &&
            windowReadAttempts < MAXIMUM_WINDOW_READ_ATTEMPTS
        ) {
            windowReadAttempts += 1
            recordDiagnostic("微信窗口未就绪，重试=$windowReadAttempts，eventWindow=$pendingEventWindowId")
            delay(WINDOW_RETRY_DELAY_MILLIS)
            snapshot = readWechatWindow()
        }
        if (snapshot == null) {
            markPaymentPageAbsent()
            return
        }

        val collected = snapshot.collected
        val textNodes = collected.nodes
        val screenWidth = snapshot.screenWidth
        recordDiagnostic(
            "采集完成：event=$eventType，eventWindow=$pendingEventWindowId，" +
                "rootWindow=${snapshot.windowId}，节点=${textNodes.size}，截断=${collected.truncated}",
        )
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
            if (WechatPaymentPageParser.containsPaymentSuccessMarker(textNodes)) {
                recordDiagnostic("支付页仍不完整：${describeNodes(textNodes)}", paymentPage = true)
                if (scheduleMerchantRetry(eventType)) {
                    Log.d(TAG, "微信支付页面尚未加载完整，准备再次读取")
                    return
                }
                Log.w(TAG, "微信支付页面重读后仍不完整，节点=${describeNodes(textNodes)}")
            }
            markPaymentPageAbsent()
            val sampleTexts = textNodes.take(10)
                .joinToString("|") { node -> node.text.trim().take(12) }
            Log.d(TAG, "微信页面未识别为支付成功页，节点数=${textNodes.size}，截断=${collected.truncated}，文本=${sampleTexts}")
            return
        }

        val counterparty = paymentPage.counterparty
        recordDiagnostic(
            "解析支付页：金额=${paymentPage.amountCents}分，商家=${counterparty ?: "未读到"}",
            paymentPage = true,
        )
        val merchantOcrSession = findMerchantOcrSession(paymentPage, observedAtMillis)
        if (merchantOcrSession != null) {
            inspectWithMerchantOcr(merchantOcrSession, paymentPage, eventType, observedAtMillis)
            return
        }
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
        if (counterparty != null) {
            nodeMerchantEventId = sourceEventId
        }

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
        recordDiagnostic(
            "摄入结果：金额=${paymentPage.amountCents}分，商家=$counterparty，结果=${outcome::class.java.simpleName}",
            paymentPage = true,
        )
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
        handOffMerchantOcrSession()
    }

    /**
     * 节点读不到商家时才使用截图识别会话。节点已读到商家、或这一页已经按节点商家入账过，
     * 都返回 null，交给原有流程处理。
     */
    private fun findMerchantOcrSession(
        paymentPage: PaymentPageObservation,
        observedAtMillis: Long,
    ): MerchantOcrSessions.Session? {
        val amountCents = paymentPage.amountCents
        if (paymentPage.counterparty != null || !merchantOcrSettings.enabled) {
            // 这笔支付若已开始截图识别，后续读取仍沿用同一会话，保证只入账一次。
            return merchantOcrSessions.find(amountCents, observedAtMillis)
        }
        merchantOcrSessions.find(amountCents, observedAtMillis)?.let { session -> return session }
        val pageEventId = captureGate.eventIdFor(amountCents = amountCents, observedAtMillis = observedAtMillis)
        if (pageEventId == nodeMerchantEventId) {
            return null
        }
        val session = merchantOcrSessions.obtain(amountCents, observedAtMillis) { pageEventId }
        startMerchantOcr(session)
        return session
    }

    private fun startMerchantOcr(session: MerchantOcrSessions.Session) {
        val request = MerchantOcrRequest(
            sourceEventId = session.sourceEventId,
            amountCents = session.amountCents,
            observedAtMillis = session.startedAtMillis,
            settings = merchantOcrSettings,
        )
        val started = merchantOcrSessions.claimScreenshot(session) {
            serviceScope.async {
                val reader = merchantScreenshotReader
                    ?: MerchantScreenshotReader(this@WechatPaymentAccessibilityService, merchantOcrDebugStore)
                        .also { created -> merchantScreenshotReader = created }
                val result = reader.read(request)
                recordDiagnostic(
                    "截图识别：金额=${session.amountCents}分，${result.summary}",
                    paymentPage = true,
                )
                result.merchant
            }
        }
        if (started) {
            recordDiagnostic("支付页缺商家，截图识别：event=${session.sourceEventId}", paymentPage = true)
        }
    }

    private suspend fun inspectWithMerchantOcr(
        session: MerchantOcrSessions.Session,
        paymentPage: PaymentPageObservation,
        eventType: Int,
        observedAtMillis: Long,
    ) {
        // 节点文本最准确，优先使用；其次沿用这笔支付已经确定的识别结果。
        paymentPage.counterparty?.let { nodeCounterparty -> merchantOcrSessions.decide(session, nodeCounterparty) }
        val knownCounterparty = paymentPage.counterparty ?: session.decidedMerchant
        if (knownCounterparty == null && scheduleMerchantRetry(eventType)) {
            Log.d(TAG, "微信支付成功页暂未读取到商家，截图识别进行中，准备再次读取：第${merchantReadAttempts}次")
            return
        }
        // 等识别结果期间不重置重试计数，这期间其他读取也会直接等同一个结果，而不是再排一轮重试。
        val counterparty = knownCounterparty ?: awaitMerchantOcr(session)
        windowReadAttempts = 0
        merchantReadAttempts = 0
        pendingMerchantRetry?.cancel()
        val trigger = if (paymentPage.counterparty != null) "节点" else "截图识别"
        ingestMerchantOcrSession(session, counterparty, observedAtMillis, trigger)
    }

    /** 页面在入账前就被离开时，后台等识别结果再入账，不丢这次支付页观测。 */
    private fun handOffMerchantOcrSession() {
        val session = merchantOcrSessions.markPageAbsent(System.currentTimeMillis()) ?: return
        recordDiagnostic("支付页已离开，等待截图识别结果后入账：event=${session.sourceEventId}", paymentPage = true)
        serviceScope.launch {
            val counterparty = awaitMerchantOcr(session)
            ingestMerchantOcrSession(session, counterparty, session.startedAtMillis, trigger = "离页")
        }
    }

    private suspend fun awaitMerchantOcr(session: MerchantOcrSessions.Session): String? {
        if (session.decided) {
            return session.decidedMerchant
        }
        val ocrResult = session.ocrResult
        val merchant = ocrResult?.let { result ->
            withTimeoutOrNull(MERCHANT_OCR_TIMEOUT_MILLIS) { result.await() }
        }
        if (ocrResult != null && !ocrResult.isCompleted && !session.decided) {
            recordDiagnostic("截图识别超时，按商家待确认处理：event=${session.sourceEventId}", paymentPage = true)
        }
        return merchantOcrSessions.decide(session, merchant)
    }

    private suspend fun ingestMerchantOcrSession(
        session: MerchantOcrSessions.Session,
        counterparty: String?,
        observedAtMillis: Long,
        trigger: String,
    ) {
        if (!merchantOcrSessions.claimIngest(session, counterparty)) {
            return
        }
        // 与节点路径完全相同的观测格式和 sourceEventId，合并、补全和分类都走现有流程。
        val raw = RawObservation(
            source = EventSource.WECHAT_PAYMENT_PAGE,
            sourceEventId = session.sourceEventId,
            title = counterparty,
            content = buildString {
                appendLine("支付成功")
                appendLine(counterparty.orEmpty())
                append('￥')
                append(formatAmount(session.amountCents))
            },
            observedAtMillis = observedAtMillis,
        )
        val application = applicationContext as YanHuoApplication
        val outcome = withContext(Dispatchers.IO) {
            application.container.automaticClassificationCoordinator.ingest(raw)
        }
        if (outcome is IngestionOutcome.Pending) {
            // 商家始终没有识别出来时明确标成“商家待确认”，绝不编造商家名。
            val reason = if (counterparty == null) "商家待确认：${outcome.reason}" else outcome.reason
            application.container.notificationManager.showPending(outcome.eventId, reason)
        }
        val message = "摄入结果（$trigger）：金额=${session.amountCents}分，商家=${counterparty ?: "待确认"}，" +
            "结果=${outcome::class.java.simpleName}"
        recordDiagnostic(message, paymentPage = true)
        if (merchantOcrSettings.debugEnabled) {
            withContext(Dispatchers.IO) {
                merchantOcrDebugStore.appendLog(session.sourceEventId, message)
            }
        }
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

    private data class WindowReadResult(
        val collected: AccessibilityNodeCollector.CollectedNodes,
        val screenWidth: Int,
        val windowId: Int,
    )

    private suspend fun readWechatWindow(): WindowReadResult? {
        val eventWindowId = pendingEventWindowId
        recordDiagnostic("开始读取：eventWindow=$eventWindowId")
        return try {
            withContext(windowReadDispatcher) {
                val root = WechatWindowResolver.findRoot(
                    activeRoot = rootInActiveWindow,
                    windows = windows.orEmpty(),
                    eventWindowId = eventWindowId,
                ) ?: return@withContext null
                val collected = AccessibilityNodeCollector.collectDetailed(
                    root,
                    MAXIMUM_NODE_COUNT,
                    visibleOnly = true,
                )
                val bounds = Rect()
                root.getBoundsInScreen(bounds)
                val screenWidth = if (bounds.width() > 0) {
                    bounds.width()
                } else {
                    resources.displayMetrics.widthPixels
                }
                WindowReadResult(collected, screenWidth, root.windowId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            recordDiagnostic("读取异常：${failure::class.java.simpleName}")
            Log.w(TAG, "读取微信窗口失败", failure)
            null
        }
    }

    private fun recordDiagnostic(message: String, paymentPage: Boolean = false) {
        val entry = "${System.currentTimeMillis()}: ${message.take(1_200)}"
        if (diagnosticHistory.size >= MAXIMUM_DIAGNOSTIC_COUNT) {
            diagnosticHistory.removeFirst()
        }
        diagnosticHistory.addLast(entry)
        if (paymentPage) {
            lastPaymentDiagnostic = entry
        }
        Log.i(TAG, message)
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<out String>?) {
        writer ?: return
        writer.println("Payment page reader diagnostics")
        writer.println("Last payment: $lastPaymentDiagnostic")
        writer.println("Read active: ${pendingRead?.isActive == true}")
        writer.println("Merchant retry active: ${pendingMerchantRetry?.isActive == true}")
        writer.println(
            "Merchant OCR: enabled=${merchantOcrSettings.enabled}, debug=${merchantOcrSettings.debugEnabled}, " +
                "crop=${merchantOcrSettings.cropTopPercent}%-${merchantOcrSettings.cropBottomPercent}%",
        )
        for (entry in diagnosticHistory) {
            writer.println(entry)
        }
    }

    private fun formatAmount(amountCents: Long): String {
        val yuan = amountCents / 100
        val fen = (amountCents % 100).toString().padStart(2, '0')
        return "$yuan.$fen"
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        nextReadRequested = false
        pendingRead?.cancel()
        pendingMerchantRetry?.cancel()
        serviceScope.cancel()
        merchantScreenshotReader?.close()
        merchantScreenshotReader = null
        stopForeground(STOP_FOREGROUND_REMOVE)
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
        private const val MAXIMUM_MERCHANT_READ_ATTEMPTS = 5

        // 截图在发现缺商家时就已发出，到这里通常已识别完；首次加载模型稍慢，留足余量。
        private const val MERCHANT_OCR_TIMEOUT_MILLIS = 4_000L
        private const val MAXIMUM_NODE_COUNT = 200
        private const val MAXIMUM_LOGGED_NODE_COUNT = 30
        private const val MAXIMUM_DIAGNOSTIC_COUNT = 64
    }
}
