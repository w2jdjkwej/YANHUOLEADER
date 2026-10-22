package com.yanhuozhang.offline.system

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
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
    val accessibilityServiceConnected: Boolean = false,
) {
    val paymentPageReaderReady: Boolean
        get() = accessibilityGranted && accessibilityServiceConnected

    val paymentPageReaderDescription: String
        get() = when {
            !accessibilityGranted -> "请开启微信支付页面读取，以提取支付成功页中的金额和商家。"
            !accessibilityServiceConnected -> "权限已开启，但读取服务未连接。请进入系统设置，将烟火账的服务关闭后重新开启。"
            else -> "读取服务已连接，可提取微信支付成功页中的金额和商家。"
        }
}

object PermissionHealthChecker {
    fun check(context: Context): PermissionHealth {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val powerManager = context.getSystemService(PowerManager::class.java)
        return PermissionHealth(
            notificationAccessGranted = context.packageName in
                NotificationManagerCompat.getEnabledListenerPackages(context),
            accessibilityGranted = isPaymentAccessibilityEnabled(context),
            accessibilityServiceConnected = isPaymentAccessibilityConnected(context),
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

    private fun isPaymentAccessibilityConnected(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val expectedComponent = ComponentName(context, WechatPaymentAccessibilityService::class.java)
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in services) {
            val info = service.resolveInfo?.serviceInfo ?: continue
            if (ComponentName(info.packageName, info.name) == expectedComponent) {
                return true
            }
        }
        return false
    }

    internal fun containsEnabledService(
        enabledServices: String,
        expectedComponent: String,
    ): Boolean {
        val expected = ComponentName.unflattenFromString(expectedComponent)?.flattenToString() ?: return false
        for (value in enabledServices.split(':')) {
            val component = ComponentName.unflattenFromString(value.trim()) ?: continue
            if (component.flattenToString().equals(expected, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
