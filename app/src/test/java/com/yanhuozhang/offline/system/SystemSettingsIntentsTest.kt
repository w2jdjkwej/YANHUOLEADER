package com.yanhuozhang.offline.system

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.view.accessibility.AccessibilityManager
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemSettingsIntentsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `builds notification and battery settings intents`() {
        val notificationAccess = SystemSettingsIntents.notificationAccess()
        val accessibility = SystemSettingsIntents.accessibility()
        val appNotifications = SystemSettingsIntents.appNotifications(context)
        val battery = SystemSettingsIntents.batteryOptimization()

        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, notificationAccess.action)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, accessibility.action)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, appNotifications.action)
        assertEquals(context.packageName, appNotifications.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, battery.action)
    }

    @Test
    fun `matches exact accessibility component in enabled service list`() {
        val expected = "com.yanhuozhang.offline/com.yanhuozhang.offline.system.WechatPaymentAccessibilityService"
        val enabled = "com.example/.OtherService:$expected"

        assertTrue(PermissionHealthChecker.containsEnabledService(enabled, expected))
        assertFalse(
            PermissionHealthChecker.containsEnabledService(
                "com.example/$expected-suffix",
                expected,
            ),
        )
    }

    @Test
    fun `short accessibility component matches full service class`() {
        assertTrue(
            PermissionHealthChecker.containsEnabledService(
                "com.example/.Other:com.yanhuozhang.offline/.system.WechatPaymentAccessibilityService",
                "com.yanhuozhang.offline/com.yanhuozhang.offline.system.WechatPaymentAccessibilityService",
            ),
        )
    }

    @Test
    fun `enabled permission without bound service is not ready`() {
        val expected = ComponentName(context, WechatPaymentAccessibilityService::class.java)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, expected.flattenToString())
        val manager = context.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabledAccessibilityServiceList(emptyList())

        val health = PermissionHealthChecker.check(context)

        assertTrue(health.accessibilityGranted)
        assertFalse(health.accessibilityServiceConnected)
        assertFalse(health.paymentPageReaderReady)
        assertTrue(health.paymentPageReaderDescription.contains("未连接"))
    }

    @Test
    fun `another bound accessibility service cannot make payment reader ready`() {
        val expected = ComponentName(context, WechatPaymentAccessibilityService::class.java)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, expected.flattenToString())
        val manager = context.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(serviceInfo("com.example", "com.example.OtherService")))

        val health = PermissionHealthChecker.check(context)

        assertTrue(health.accessibilityGranted)
        assertFalse(health.accessibilityServiceConnected)
        assertFalse(health.paymentPageReaderReady)
    }

    @Test
    fun `bound payment service with permission is ready`() {
        val expected = ComponentName(context, WechatPaymentAccessibilityService::class.java)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, expected.flattenToString())
        val manager = context.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(serviceInfo(expected.packageName, expected.className)))

        val health = PermissionHealthChecker.check(context)

        assertTrue(health.accessibilityGranted)
        assertTrue(health.accessibilityServiceConnected)
        assertTrue(health.paymentPageReaderReady)
        assertTrue(health.paymentPageReaderDescription.contains("已连接"))
    }

    private fun serviceInfo(packageName: String, className: String): AccessibilityServiceInfo {
        val resolveInfo = ResolveInfo()
        resolveInfo.serviceInfo = ServiceInfo().apply {
            this.packageName = packageName
            name = className
        }
        val info = AccessibilityServiceInfo()
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        ReflectionHelpers.setField(info, "mResolveInfo", resolveInfo)
        return info
    }
    @Test
    fun `xiaomi auto start intent targets security center`() {
        val intent = SystemSettingsIntents.xiaomiAutoStart(context)

        assertEquals("com.miui.securitycenter", intent.component?.packageName)
        assertTrue(intent.component?.className?.contains("AutoStart") == true)
    }
}
