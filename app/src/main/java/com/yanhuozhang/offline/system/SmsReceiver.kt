package com.yanhuozhang.offline.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.yanhuozhang.offline.YanHuoApplication
import com.yanhuozhang.offline.data.IngestionOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) {
            return
        }
        val sender = messages.first().displayOriginatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { message -> message.displayMessageBody.orEmpty() }
        val receivedAtMillis = messages.minOf { message -> message.timestampMillis }
        val raw = SmsObservationFactory.create(sender, body, receivedAtMillis) ?: return
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val application = context.applicationContext as YanHuoApplication
                val outcome = application.container.automaticClassificationCoordinator.ingest(raw)
                if (outcome is IngestionOutcome.Pending) {
                    application.container.notificationManager.showPending(outcome.eventId, outcome.reason)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
