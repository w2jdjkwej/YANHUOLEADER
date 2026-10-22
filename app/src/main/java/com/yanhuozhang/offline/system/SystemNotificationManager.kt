package com.yanhuozhang.offline.system

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yanhuozhang.offline.R

class SystemNotificationManager(private val context: Context) {
    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                PENDING_CHANNEL_ID,
                "待确认账单",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "仅在交易需要确认或识别异常时提醒"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                REMINDER_CHANNEL_ID,
                "待确认汇总",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CLASSIFICATION_CHANNEL_ID,
                "待分类账单",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "本地规则和 DeepSeek 都无法可靠分类时提醒"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                PAYMENT_READER_CHANNEL_ID,
                "支付页面识别运行状态",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "保持支付页面识别在后台运行，避免 HyperOS 冻结后漏记账；可在系统里隐藏此通知"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                MONTHLY_ANALYSIS_CHANNEL_ID,
                "月度账单分析",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "月底分析生成或失败时提醒"
            },
        )
    }

    fun showPending(eventId: Long, reason: String) {
        notify(
            notificationId = eventId.hashCode(),
            channelId = PENDING_CHANNEL_ID,
            title = "有一笔交易需要确认",
            text = reason,
        )
    }

    fun showPendingSummary(count: Int) {
        notify(
            notificationId = SUMMARY_NOTIFICATION_ID,
            channelId = REMINDER_CHANNEL_ID,
            title = "烟火账待确认提醒",
            text = "还有 $count 笔交易需要处理",
        )
    }

    fun showClassificationRequired(recordId: Long, reason: String) {
        notify(
            notificationId = CLASSIFICATION_NOTIFICATION_BASE + recordId.hashCode(),
            channelId = CLASSIFICATION_CHANNEL_ID,
            title = "有一笔账单需要手动分类",
            text = reason,
        )
    }

    fun cancelClassificationRequired(recordId: Long) {
        cancel(CLASSIFICATION_NOTIFICATION_BASE + recordId.hashCode())
    }

    fun cancelPending(eventId: Long) {
        cancel(eventId.hashCode())
    }

    fun showMonthlyAnalysisReady(monthKey: String) {
        notify(
            notificationId = MONTHLY_ANALYSIS_NOTIFICATION_ID,
            channelId = MONTHLY_ANALYSIS_CHANNEL_ID,
            title = "$monthKey 月度账单分析已生成",
            text = "打开统计页即可查看本月分类支出分析",
        )
    }

    fun showMonthlyAnalysisFailed(monthKey: String, reason: String) {
        notify(
            notificationId = MONTHLY_ANALYSIS_NOTIFICATION_ID,
            channelId = MONTHLY_ANALYSIS_CHANNEL_ID,
            title = "$monthKey 月度账单分析未生成",
            text = reason,
        )
    }

    /** 无障碍服务进入前台服务时使用的常驻通知。没有通知权限时系统不显示它，但前台服务照常生效。 */
    fun paymentReaderNotification(): Notification {
        return NotificationCompat.Builder(context, PAYMENT_READER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("烟火账正在识别微信支付")
            .setContentText("保持后台运行，避免系统冻结后漏记账")
            .setContentIntent(launchPendingIntent())
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun launchPendingIntent(): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        return launchIntent?.let { intent ->
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    private fun notify(notificationId: Int, channelId: String, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(launchPendingIntent())
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun cancel(notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    companion object {
        const val PENDING_CHANNEL_ID = "pending_transactions"
        const val REMINDER_CHANNEL_ID = "pending_reminders"
        const val CLASSIFICATION_CHANNEL_ID = "classification_required"
        const val MONTHLY_ANALYSIS_CHANNEL_ID = "monthly_analysis"
        const val PAYMENT_READER_CHANNEL_ID = "payment_reader"
        // 待确认、待分类通知按事件或账单 ID 递增编号，这里取一个远离它们的固定值。
        const val PAYMENT_READER_NOTIFICATION_ID = 1_900_000_001
        private const val CLASSIFICATION_NOTIFICATION_BASE = 30_000
        private const val MONTHLY_ANALYSIS_NOTIFICATION_ID = 40_000
        private const val SUMMARY_NOTIFICATION_ID = 20_000
    }
}
