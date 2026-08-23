package com.yanhuozhang.offline.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yanhuozhang.offline.backup.BackupSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    val appLockEnabled: Boolean = true,
    val lockTimeoutSeconds: Int = 60,
    val deepSeekEnabled: Boolean = false,
    val deepSeekApiKey: String = "",
    val deepSeekModel: DeepSeekModel = DeepSeekModel.FLASH,
    val deepSeekThinkingMode: DeepSeekThinkingMode = DeepSeekThinkingMode.DISABLED,
) {
    fun toBackupSettings(): BackupSettings {
        return BackupSettings(
            reminderEnabled = reminderEnabled,
            reminderHour = reminderHour,
            reminderMinute = reminderMinute,
            appLockEnabled = appLockEnabled,
            lockTimeoutSeconds = lockTimeoutSeconds,
            deepSeekEnabled = deepSeekEnabled,
            deepSeekApiKey = deepSeekApiKey,
            deepSeekModel = deepSeekModel.apiValue,
            deepSeekThinkingMode = deepSeekThinkingMode.apiValue,
        )
    }
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
        val reminderEnabled = booleanPreferencesKey("reminder_enabled")
        val reminderHour = intPreferencesKey("reminder_hour")
        val reminderMinute = intPreferencesKey("reminder_minute")
        val appLockEnabled = booleanPreferencesKey("app_lock_enabled")
        val lockTimeoutSeconds = intPreferencesKey("lock_timeout_seconds")
        val deepSeekEnabled = booleanPreferencesKey("deepseek_enabled")
        val deepSeekApiKey = stringPreferencesKey("deepseek_api_key")
        val deepSeekModel = stringPreferencesKey("deepseek_model")
        val deepSeekThinkingMode = stringPreferencesKey("deepseek_thinking_mode")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            onboardingCompleted = preferences[Keys.onboardingCompleted] ?: false,
            reminderEnabled = preferences[Keys.reminderEnabled] ?: true,
            reminderHour = preferences[Keys.reminderHour] ?: 20,
            reminderMinute = preferences[Keys.reminderMinute] ?: 0,
            appLockEnabled = preferences[Keys.appLockEnabled] ?: true,
            lockTimeoutSeconds = preferences[Keys.lockTimeoutSeconds] ?: 60,
            deepSeekEnabled = preferences[Keys.deepSeekEnabled] ?: false,
            deepSeekApiKey = preferences[Keys.deepSeekApiKey].orEmpty(),
            deepSeekModel = DeepSeekModel.fromApiValue(
                preferences[Keys.deepSeekModel] ?: DeepSeekModel.FLASH.apiValue,
            ),
            deepSeekThinkingMode = DeepSeekThinkingMode.fromApiValue(
                preferences[Keys.deepSeekThinkingMode]
                    ?: DeepSeekThinkingMode.DISABLED.apiValue,
            ),
        )
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.onboardingCompleted] = completed
        }
    }

    suspend fun setReminder(enabled: Boolean, hour: Int, minute: Int) {
        require(hour in 0..23)
        require(minute in 0..59)
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.reminderEnabled] = enabled
            preferences[Keys.reminderHour] = hour
            preferences[Keys.reminderMinute] = minute
        }
    }

    suspend fun setAppLock(enabled: Boolean, timeoutSeconds: Int) {
        require(timeoutSeconds >= 0)
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.appLockEnabled] = enabled
            preferences[Keys.lockTimeoutSeconds] = timeoutSeconds
        }
    }

    suspend fun setDeepSeek(
        enabled: Boolean,
        apiKey: String,
        model: DeepSeekModel,
        thinkingMode: DeepSeekThinkingMode,
    ) {
        val trimmedApiKey = apiKey.trim()
        require(!enabled || trimmedApiKey.isNotEmpty()) { "请先填写 DeepSeek API Key" }
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.deepSeekEnabled] = enabled
            preferences[Keys.deepSeekApiKey] = trimmedApiKey
            preferences[Keys.deepSeekModel] = model.apiValue
            preferences[Keys.deepSeekThinkingMode] = thinkingMode.apiValue
        }
    }

    suspend fun restoreBackupSettings(settings: BackupSettings) {
        setReminder(settings.reminderEnabled, settings.reminderHour, settings.reminderMinute)
        setAppLock(settings.appLockEnabled, settings.lockTimeoutSeconds)
        setDeepSeek(
            enabled = settings.deepSeekEnabled,
            apiKey = settings.deepSeekApiKey,
            model = DeepSeekModel.fromApiValue(settings.deepSeekModel),
            thinkingMode = DeepSeekThinkingMode.fromApiValue(settings.deepSeekThinkingMode),
        )
    }
}
