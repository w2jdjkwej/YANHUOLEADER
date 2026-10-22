package com.yanhuozhang.offline.ui

import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yanhuozhang.offline.system.PermissionHealth
import com.yanhuozhang.offline.system.PermissionHealthChecker

@Composable
internal fun rememberPermissionHealth(): MutableState<PermissionHealth> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val health = remember(context) { mutableStateOf(PermissionHealthChecker.check(context)) }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                health.value = PermissionHealthChecker.check(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val listener = if (Build.VERSION.SDK_INT >= 33) {
            val servicesListener = AccessibilityManager.AccessibilityServicesStateChangeListener {
                health.value = PermissionHealthChecker.check(context)
            }
            manager.addAccessibilityServicesStateChangeListener(context.mainExecutor, servicesListener)
            servicesListener
        } else {
            null
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (Build.VERSION.SDK_INT >= 33 && listener != null) {
                manager.removeAccessibilityServicesStateChangeListener(listener)
            }
        }
    }
    return health
}