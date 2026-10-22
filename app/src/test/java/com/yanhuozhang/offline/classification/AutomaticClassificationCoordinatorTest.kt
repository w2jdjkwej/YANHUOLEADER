package com.yanhuozhang.offline.classification

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yanhuozhang.offline.core.EventSource
import com.yanhuozhang.offline.core.RawObservation
import com.yanhuozhang.offline.data.AppDatabase
import com.yanhuozhang.offline.data.DatabaseSeeder
import com.yanhuozhang.offline.data.IngestionOutcome
import com.yanhuozhang.offline.data.LedgerRepository
import com.yanhuozhang.offline.settings.SettingsRepository
import com.yanhuozhang.offline.system.SystemNotificationManager
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AutomaticClassificationCoordinatorTest {
    private lateinit var classificationJob: Job
    private lateinit var database: AppDatabase
    private lateinit var coordinator: AutomaticClassificationCoordinator
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { DatabaseSeeder.seedDefaultCategories(database.categoryDao()) }
        classificationJob = SupervisorJob()
        coordinator = AutomaticClassificationCoordinator(
            repository = LedgerRepository(database, ZoneId.of("Asia/Shanghai")),
            settingsRepository = SettingsRepository(context),
            classifier = DeepSeekCategoryClassifier(),
            notificationManager = SystemNotificationManager(context),
            applicationScope = CoroutineScope(classificationJob + Dispatchers.IO),
        )
        notificationManager = context.getSystemService(NotificationManager::class.java)
    }

    @After
    fun tearDown() {
        runBlocking { classificationJob.cancelAndJoin() }
        database.close()
    }

    @Test
    fun `stale classification notification is removed after page attach classifies the record`() = runBlocking {
        val notificationOutcome = coordinator.ingest(
            paymentNotification("notification-key-1", 1_000_000, "已支付￥12.00"),
        ) as IngestionOutcome.Created
        val notificationId = 30_000 + notificationOutcome.recordId.hashCode()

        awaitUntil { shadowOf(notificationManager).getNotification(notificationId) != null }

        val attachOutcome = coordinator.ingest(
            paymentPage("满座儿餐饮服务（北京）有限公司", "12.00", 1_003_000),
        )
        assertTrue(attachOutcome is IngestionOutcome.Attached)

        awaitUntil { shadowOf(notificationManager).getNotification(notificationId) == null }
    }

    @Test
    fun `no stale notification when page attach lands before classification job posts`() = runBlocking {
        val notificationOutcome = coordinator.ingest(
            paymentNotification("notification-key-2", 2_000_000, "已支付￥8.00"),
        ) as IngestionOutcome.Created
        val notificationId = 30_000 + notificationOutcome.recordId.hashCode()

        val attachOutcome = coordinator.ingest(
            paymentPage("沙县小吃店", "8.00", 2_003_000),
        )
        assertTrue(attachOutcome is IngestionOutcome.Attached)

        delay(500)
        assertNull(shadowOf(notificationManager).getNotification(notificationId))
    }

    @Test
    fun `unclassified record with deepseek disabled still gets classification notification`() = runBlocking {
        val outcome = coordinator.ingest(
            paymentNotification("notification-key-3", 3_000_000, "已支付￥5.00"),
        ) as IngestionOutcome.Created
        val notificationId = 30_000 + outcome.recordId.hashCode()

        awaitUntil { shadowOf(notificationManager).getNotification(notificationId) != null }
    }

    private suspend fun awaitUntil(
        timeoutMillis: Long = 5_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("等待条件超时")
            }
            delay(20)
        }
    }

    private fun paymentNotification(
        sourceEventId: String,
        observedAtMillis: Long,
        content: String,
    ): RawObservation {
        return RawObservation(
            source = EventSource.WECHAT_NOTIFICATION,
            sourceEventId = sourceEventId,
            title = "微信支付",
            content = content,
            observedAtMillis = observedAtMillis,
        )
    }

    private fun paymentPage(
        counterparty: String?,
        amount: String,
        observedAtMillis: Long,
    ): RawObservation {
        return RawObservation(
            source = EventSource.WECHAT_PAYMENT_PAGE,
            sourceEventId = "page-$observedAtMillis-$amount-${counterparty.orEmpty()}",
            title = counterparty,
            content = "支付成功\n${counterparty.orEmpty()}\n￥$amount",
            observedAtMillis = observedAtMillis,
        )
    }
}
