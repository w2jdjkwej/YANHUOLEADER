package com.yanhuozhang.offline.system

import android.content.Context
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
    fun `xiaomi auto start intent targets security center`() {
        val intent = SystemSettingsIntents.xiaomiAutoStart(context)

        assertEquals("com.miui.securitycenter", intent.component?.packageName)
        assertTrue(intent.component?.className?.contains("AutoStart") == true)
    }
}
