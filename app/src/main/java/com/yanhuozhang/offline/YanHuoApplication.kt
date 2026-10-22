package com.yanhuozhang.offline

import android.app.Application
import com.yanhuozhang.offline.backup.BackupRepository
import com.yanhuozhang.offline.analysis.DeepSeekMonthlyAnalysisClient
import com.yanhuozhang.offline.analysis.MonthlyAnalysisRepository
import com.yanhuozhang.offline.classification.AutomaticClassificationCoordinator
import com.yanhuozhang.offline.classification.DeepSeekCategoryClassifier
import com.yanhuozhang.offline.data.AppDatabase
import com.yanhuozhang.offline.data.DatabaseSeeder
import com.yanhuozhang.offline.data.LedgerRepository
import com.yanhuozhang.offline.recognition.BitmapScreenshotImageEncoder
import com.yanhuozhang.offline.recognition.DeepSeekScreenshotRecognizer
import com.yanhuozhang.offline.recognition.ScreenshotRecognitionService
import com.yanhuozhang.offline.recognition.SharedScreenshotInbox
import com.yanhuozhang.offline.settings.SettingsRepository
import com.yanhuozhang.offline.system.SystemNotificationManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YanHuoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notificationManager.createChannels()
        container.applicationScope.launch {
            DatabaseSeeder.seedDefaultCategories(container.database.categoryDao())
            DatabaseSeeder.backfillCategoryStyles(container.database.categoryDao())
            reconcileStaleNotifications()
        }
    }

    private suspend fun reconcileStaleNotifications() {
        val uncategorizedId = container.database.categoryDao().findUncategorizedExpense()?.id
            ?: return
        for (recordId in container.ledgerRepository.findStaleClassificationRecordIds(uncategorizedId)) {
            container.notificationManager.cancelClassificationRequired(recordId)
        }
        for (eventId in container.ledgerRepository.findResolvedEventIds()) {
            container.notificationManager.cancelPending(eventId)
        }
    }
}

class AppContainer(application: Application) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database: AppDatabase = AppDatabase.getInstance(application)
    val settingsRepository = SettingsRepository(application)
    val ledgerRepository = LedgerRepository(database)
    val backupRepository = BackupRepository(database)
    val monthlyAnalysisRepository = MonthlyAnalysisRepository(
        database = database,
        settingsRepository = settingsRepository,
        client = DeepSeekMonthlyAnalysisClient(),
    )
    val screenshotRecognitionService = ScreenshotRecognitionService(
        settingsRepository = settingsRepository,
        ledgerRepository = ledgerRepository,
        imageEncoder = BitmapScreenshotImageEncoder(),
        recognizer = DeepSeekScreenshotRecognizer(),
    )
    val sharedScreenshotInbox = SharedScreenshotInbox(File(application.cacheDir, "shared-screenshots"))
    val notificationManager = SystemNotificationManager(application)
    val automaticClassificationCoordinator = AutomaticClassificationCoordinator(
        repository = ledgerRepository,
        settingsRepository = settingsRepository,
        classifier = DeepSeekCategoryClassifier(),
        notificationManager = notificationManager,
        applicationScope = applicationScope,
    )
}
