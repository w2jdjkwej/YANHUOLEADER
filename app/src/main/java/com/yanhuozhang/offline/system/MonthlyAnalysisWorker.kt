package com.yanhuozhang.offline.system

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yanhuozhang.offline.YanHuoApplication
import com.yanhuozhang.offline.analysis.MonthlyAnalysisGenerationResult
import java.time.Duration
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

data class MonthlyAnalysisSchedule(
    val month: YearMonth,
    val runAt: ZonedDateTime,
)

object MonthlyAnalysisTimeCalculator {
    private val runTime = LocalTime.of(23, 30)

    fun next(now: ZonedDateTime): MonthlyAnalysisSchedule {
        val currentMonth = YearMonth.from(now)
        val lastDay = currentMonth.atEndOfMonth()
        val plannedRunAt = lastDay.atTime(runTime).atZone(now.zone)
        val runAt = if (now.toLocalDate() == lastDay && !now.isBefore(plannedRunAt)) {
            now.plusSeconds(5)
        } else {
            plannedRunAt
        }
        return MonthlyAnalysisSchedule(currentMonth, runAt)
    }
}

class MonthlyAnalysisWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val monthKey = inputData.getString(INPUT_MONTH_KEY) ?: return Result.failure()
        val month = runCatching { YearMonth.parse(monthKey) }.getOrNull()
            ?: return Result.failure()
        val application = applicationContext as YanHuoApplication
        val generationResult = try {
            application.container.monthlyAnalysisRepository.generate(month)
        } catch (error: Exception) {
            MonthlyAnalysisGenerationResult.Failed(
                error.message ?: "月度账单分析失败",
            )
        }
        if (generationResult is MonthlyAnalysisGenerationResult.Failed &&
            runAttemptCount < MAX_RETRY_COUNT
        ) {
            return Result.retry()
        }

        when (generationResult) {
            is MonthlyAnalysisGenerationResult.Generated -> {
                application.container.notificationManager.showMonthlyAnalysisReady(monthKey)
            }
            is MonthlyAnalysisGenerationResult.Failed -> {
                application.container.notificationManager.showMonthlyAnalysisFailed(
                    monthKey,
                    generationResult.reason,
                )
            }
            is MonthlyAnalysisGenerationResult.AlreadyCurrent,
            MonthlyAnalysisGenerationResult.DeepSeekUnavailable,
            MonthlyAnalysisGenerationResult.NoExpense,
            -> Unit
        }
        MonthlyAnalysisScheduler.scheduleNext(
            context = application,
            now = ZonedDateTime.now().plusDays(1),
        )
        return Result.success()
    }

    companion object {
        const val INPUT_MONTH_KEY = "month_key"
        private const val MAX_RETRY_COUNT = 2
    }
}

object MonthlyAnalysisScheduler {
    fun scheduleNext(
        context: Context,
        now: ZonedDateTime = ZonedDateTime.now(),
    ) {
        val schedule = MonthlyAnalysisTimeCalculator.next(now)
        val delay = Duration.between(now, schedule.runAt).coerceAtLeast(Duration.ZERO)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<MonthlyAnalysisWorker>()
            .setInputData(
                workDataOf(MonthlyAnalysisWorker.INPUT_MONTH_KEY to schedule.month.toString()),
            )
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "monthly-analysis-${schedule.month}",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
