package com.yanhuozhang.offline.system

import com.yanhuozhang.offline.settings.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {
    @Test
    fun `completed onboarding locks on first protected start`() {
        val settings = AppSettings(onboardingCompleted = true, appLockEnabled = true)

        assertTrue(shouldLockApp(settings, lastStoppedAtMillis = 0, nowMillis = 10_000))
    }

    @Test
    fun `onboarding and disabled lock remain accessible`() {
        assertFalse(shouldLockApp(AppSettings(onboardingCompleted = false), 0, 10_000))
        assertFalse(
            shouldLockApp(
                AppSettings(onboardingCompleted = true, appLockEnabled = false),
                0,
                10_000,
            ),
        )
    }

    @Test
    fun `background timeout controls relocking`() {
        val settings = AppSettings(
            onboardingCompleted = true,
            appLockEnabled = true,
            lockTimeoutSeconds = 60,
        )

        assertFalse(shouldLockApp(settings, lastStoppedAtMillis = 10_000, nowMillis = 69_999))
        assertTrue(shouldLockApp(settings, lastStoppedAtMillis = 10_000, nowMillis = 70_000))
    }
}
