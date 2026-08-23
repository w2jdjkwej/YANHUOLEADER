package com.yanhuozhang.offline.system

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object SystemSettingsIntents {
    fun notificationAccess(): Intent {
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    fun accessibility(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    fun appNotifications(context: Context): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    }

    fun batteryOptimization(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    fun xiaomiAutoStart(context: Context): Intent {
        return Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
            putExtra("package_name", context.packageName)
        }
    }

    fun appDetails(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    }

    /**
     * HyperOS 上部分系统设置页解析不到、或不允许外部拉起，会抛
     * ActivityNotFoundException / SecurityException。裸调 startActivity 会直接崩溃，
     * 所以统一走这里：先试目标页，失败退到应用详情页，再失败交给调用方提示。
     */
    fun open(context: Context, intent: Intent): Boolean {
        if (start(context, intent)) {
            return true
        }
        return start(context, appDetails(context))
    }

    private fun start(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}

data class PermissionHealth(
    val notificationAccessGranted: Boolean,
    val accessibilityGranted: Boolean,
    val smsPermissionGranted: Boolean,
    val appNotificationsGranted: Boolean,
    val batteryOptimizationDisabled: Boolean,
)

object PermissionHealthChecker {
    fun check(context: Context): PermissionHealth {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val powerManager = context.getSystemService(PowerManager::class.java)
        return PermissionHealth(
            notificationAccessGranted = context.packageName in
                NotificationManagerCompat.getEnabledListenerPackages(context),
            accessibilityGranted = isPaymentAccessibilityEnabled(context),
            smsPermissionGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECEIVE_SMS,
            ) == PackageManager.PERMISSION_GRANTED,
            appNotificationsGranted = notificationManager.areNotificationsEnabled(),
            batteryOptimizationDisabled = powerManager.isIgnoringBatteryOptimizations(context.packageName),
        )
    }

    private fun isPaymentAccessibilityEnabled(context: Context): Boolean {
        val expectedComponent = ComponentName(
            context,
            WechatPaymentAccessibilityService::class.java,
        ).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return containsEnabledService(enabledServices, expectedComponent)
    }

    internal fun containsEnabledService(
        enabledServices: String,
        expectedComponent: String,
    ): Boolean {
        return enabledServices.split(':').any { component ->
            component.equals(expectedComponent, ignoreCase = true)
        }
    }
}
