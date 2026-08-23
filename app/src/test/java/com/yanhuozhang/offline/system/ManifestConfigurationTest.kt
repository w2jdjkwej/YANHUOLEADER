package com.yanhuozhang.offline.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import com.yanhuozhang.offline.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestConfigurationTest {
    @Test
    fun `declares only required sensitive permissions and system components`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS,
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()
        val services = packageInfo.services.orEmpty().associateBy { it.name }
        val receivers = packageInfo.receivers.orEmpty().map { it.name }.toSet()

        assertTrue(context.packageName.endsWith(".debug"))
        assertTrue(Manifest.permission.RECEIVE_SMS in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertTrue(Manifest.permission.RECEIVE_BOOT_COMPLETED in permissions)
        assertTrue(Manifest.permission.INTERNET in permissions)
        assertTrue(Manifest.permission.ACCESS_NETWORK_STATE in permissions)
        assertFalse(Manifest.permission.READ_SMS in permissions)
        assertEquals(
            Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE,
            services.getValue(WechatNotificationListener::class.java.name).permission,
        )
        assertEquals(
            Manifest.permission.BIND_ACCESSIBILITY_SERVICE,
            services.getValue(WechatPaymentAccessibilityService::class.java.name).permission,
        )
        assertTrue(SmsReceiver::class.java.name in receivers)
        assertTrue(BootReceiver::class.java.name in receivers)
    }

    @Test
    fun `payment accessibility service listens for window changes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parser = context.resources.getXml(R.xml.wechat_payment_accessibility_service)

        try {
            while (parser.eventType != XmlPullParser.START_TAG && parser.eventType != XmlPullParser.END_DOCUMENT) {
                parser.next()
            }
            val eventTypes = parser.getAttributeIntValue(
                ANDROID_NAMESPACE,
                "accessibilityEventTypes",
                0,
            )

            assertTrue(eventTypes and AccessibilityEvent.TYPE_WINDOWS_CHANGED != 0)
        } finally {
            parser.close()
        }
    }

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
