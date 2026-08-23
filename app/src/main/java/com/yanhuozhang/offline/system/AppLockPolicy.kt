package com.yanhuozhang.offline.system

import com.yanhuozhang.offline.settings.AppSettings

internal fun shouldLockApp(
    settings: AppSettings,
    lastStoppedAtMillis: Long,
    nowMillis: Long,
): Boolean {
    if (!settings.onboardingCompleted || !settings.appLockEnabled) {
        return false
    }
    if (lastStoppedAtMillis == 0L) {
        return true
    }

    val elapsedMillis = (nowMillis - lastStoppedAtMillis).coerceAtLeast(0L)
    return elapsedMillis >= settings.lockTimeoutSeconds * 1_000L
}
