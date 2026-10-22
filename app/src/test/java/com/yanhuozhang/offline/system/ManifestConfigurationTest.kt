package com.yanhuozhang.offline.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import com.yanhuozhang.offline.R
import com.yanhuozhang.offline.ShareScreenshotActivity
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
    fun `payment accessibility service may run as special use foreground service`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()
        val service = packageInfo.services.orEmpty()
            .single { info -> info.name == WechatPaymentAccessibilityService::class.java.name }

        assertTrue(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE in permissions)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
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

    @Test
    fun `payment accessibility service can take screenshots for merchant OCR`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parser = context.resources.getXml(R.xml.wechat_payment_accessibility_service)

        try {
            while (parser.eventType != XmlPullParser.START_TAG && parser.eventType != XmlPullParser.END_DOCUMENT) {
                parser.next()
            }

            assertTrue(parser.getAttributeBooleanValue(ANDROID_NAMESPACE, "canTakeScreenshot", false))
        } finally {
            parser.close()
        }
    }

    @Test
    fun `shared images open the screenshot recognition entry`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            setPackage(context.packageName)
        }

        val activities = context.packageManager.queryIntentActivities(intent, 0)

        assertEquals(
            listOf(ShareScreenshotActivity::class.java.name),
            activities.map { info -> info.activityInfo.name },
        )
    }

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
