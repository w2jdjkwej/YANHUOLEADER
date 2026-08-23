package com.yanhuozhang.offline.system

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemNotificationManagerTest {
    private lateinit var manager: SystemNotificationManager
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = SystemNotificationManager(context)
        manager.createChannels()
        notificationManager = context.getSystemService(NotificationManager::class.java)
    }

    @Test
    fun `classification required notification is cancelled after record is classified`() {
        val recordId = 123L
        manager.showClassificationRequired(recordId, "本地规则无法判断，请手动选择分类")

        assertNotNull(shadowOf(notificationManager).getNotification(30_000 + recordId.hashCode()))

        manager.cancelClassificationRequired(recordId)

        assertNull(shadowOf(notificationManager).getNotification(30_000 + recordId.hashCode()))
    }

    @Test
    fun `pending notification is cancelled after pending event is resolved`() {
        val eventId = 456L
        manager.showPending(eventId, "识别置信度不足")

        assertNotNull(shadowOf(notificationManager).getNotification(eventId.hashCode()))

        manager.cancelPending(eventId)

        assertNull(shadowOf(notificationManager).getNotification(eventId.hashCode()))
    }
}
