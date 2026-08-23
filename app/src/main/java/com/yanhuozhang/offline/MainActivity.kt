package com.yanhuozhang.offline

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yanhuozhang.offline.system.ReminderScheduler
import com.yanhuozhang.offline.system.MonthlyAnalysisScheduler
import com.yanhuozhang.offline.system.shouldLockApp
import com.yanhuozhang.offline.ui.MainViewModel
import com.yanhuozhang.offline.ui.YanHuoApp
import com.yanhuozhang.offline.ui.theme.YanHuoTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private var locked by mutableStateOf(true)
    private var lastStoppedAtMillis = 0L
    private var promptVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YanHuoTheme {
                if (locked) {
                    LockedScreen(onUnlock = ::showAuthenticationPrompt)
                } else {
                    val mainViewModel: MainViewModel = viewModel()
                    YanHuoApp(mainViewModel)
                }
            }
        }

        lifecycleScope.launch {
            val settings = (application as YanHuoApplication).container.settingsRepository.settings.first()
            ReminderScheduler.schedule(this@MainActivity, settings.reminderEnabled, settings.reminderHour, settings.reminderMinute)
            MonthlyAnalysisScheduler.scheduleNext(this@MainActivity)
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val settings = (application as YanHuoApplication).container.settingsRepository.settings.first()
            locked = shouldLockApp(
                settings = settings,
                lastStoppedAtMillis = lastStoppedAtMillis,
                nowMillis = SystemClock.elapsedRealtime(),
            )
            if (locked) {
                showAuthenticationPrompt()
            }
        }
    }

    override fun onStop() {
        lastStoppedAtMillis = SystemClock.elapsedRealtime()
        super.onStop()
    }

    private fun showAuthenticationPrompt() {
        if (promptVisible) {
            return
        }
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuthenticate = BiometricManager.from(this).canAuthenticate(authenticators)
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            locked = false
            return
        }

        promptVisible = true
        val prompt = BiometricPrompt(
            this,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptVisible = false
                    locked = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    promptVisible = false
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁烟火账")
            .setSubtitle("使用指纹、人脸或设备锁屏凭据")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(promptInfo)
    }
}

@androidx.compose.runtime.Composable
private fun LockedScreen(onUnlock: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                Text("烟火账已锁定", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "使用生物识别或设备凭据继续",
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onUnlock,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("解锁")
                }
            }
        }
    }
}
