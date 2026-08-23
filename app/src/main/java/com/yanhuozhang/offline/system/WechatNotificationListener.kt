package com.yanhuozhang.offline.system

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.yanhuozhang.offline.YanHuoApplication
import com.yanhuozhang.offline.data.IngestionOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class WechatNotificationListener : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
        val extras = statusBarNotification.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString().orEmpty()
        val raw = NotificationObservationFactory.create(
            NotificationPayload(
                packageName = statusBarNotification.packageName,
                notificationKey = statusBarNotification.key,
                title = title,
                text = text,
                postedAtMillis = statusBarNotification.postTime,
            ),
        ) ?: return

        serviceScope.launch {
            val application = applicationContext as YanHuoApplication
            val outcome = application.container.automaticClassificationCoordinator.ingest(raw)
            if (outcome is IngestionOutcome.Pending) {
                application.container.notificationManager.showPending(outcome.eventId, outcome.reason)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
