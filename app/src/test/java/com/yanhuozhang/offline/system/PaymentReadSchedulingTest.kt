package com.yanhuozhang.offline.system

import android.app.Application
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@Suppress("DEPRECATION")
class PaymentReadSchedulingTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var service: WechatPaymentAccessibilityService

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        service = Robolectric.buildService(WechatPaymentAccessibilityService::class.java).create().get()
    }

    @After
    fun tearDown() {
        service.onDestroy()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun burstOfWechatEventsKeepsOneScheduledRead() {
        service.onAccessibilityEvent(wechatEvent())
        val firstJob = scheduledRead()
        assertTrue(firstJob?.isActive == true)

        for (index in 0 until 100) {
            service.onAccessibilityEvent(wechatEvent())
        }

        assertSame(firstJob, scheduledRead())
        assertTrue(firstJob?.isActive == true)
    }

    @Test
    fun destroyingServiceStopsScheduledRead() {
        service.onAccessibilityEvent(wechatEvent())
        val job = scheduledRead()

        service.onDestroy()
        dispatcher.scheduler.runCurrent()

        assertFalse(job?.isActive == true)
    }

    private fun scheduledRead(): Job? {
        return ReflectionHelpers.getField(service, "pendingRead")
    }

    private fun wechatEvent(): AccessibilityEvent {
        return AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.tencent.mm"
        }
    }
}
