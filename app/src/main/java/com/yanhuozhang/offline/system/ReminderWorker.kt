package com.yanhuozhang.offline.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yanhuozhang.offline.YanHuoApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class PendingReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val application = applicationContext as YanHuoApplication
        val count = application.container.database.eventDao().pendingCount()
        if (count > 0) {
            application.container.notificationManager.showPendingSummary(count)
        }
        return Result.success()
    }
}

object ReminderScheduler {
    private const val UNIQUE_WORK_NAME = "daily-pending-reminder"

    fun schedule(context: Context, enabled: Boolean, hour: Int, minute: Int) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val delay = ReminderTimeCalculator.delayUntilNext(ZonedDateTime.now(), hour, minute)
        val request = PeriodicWorkRequestBuilder<PendingReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val pendingResult = goAsync()
        val application = context.applicationContext as YanHuoApplication
        application.container.applicationScope.launch {
            try {
                val settings = application.container.settingsRepository.settings.first()
                ReminderScheduler.schedule(
                    context = application,
                    enabled = settings.reminderEnabled,
                    hour = settings.reminderHour,
                    minute = settings.reminderMinute,
                )
                MonthlyAnalysisScheduler.scheduleNext(application)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
