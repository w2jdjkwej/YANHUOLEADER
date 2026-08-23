package com.yanhuozhang.offline.classification

import com.yanhuozhang.offline.core.RawObservation
import com.yanhuozhang.offline.data.IngestionOutcome
import com.yanhuozhang.offline.data.LedgerRepository
import com.yanhuozhang.offline.settings.SettingsRepository
import com.yanhuozhang.offline.system.SystemNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AutomaticClassificationCoordinator(
    private val repository: LedgerRepository,
    private val settingsRepository: SettingsRepository,
    private val classifier: DeepSeekCategoryClassifier,
    private val notificationManager: SystemNotificationManager,
    private val applicationScope: CoroutineScope,
) {
    private val runningRecordIds = mutableSetOf<Long>()

    suspend fun ingest(raw: RawObservation): IngestionOutcome {
        val outcome = repository.ingest(raw)
        if (outcome is IngestionOutcome.Attached && !outcome.needsRemoteClassification) {
            notificationManager.cancelClassificationRequired(outcome.recordId)
        }
        val recordId = when (outcome) {
            is IngestionOutcome.Created ->
                outcome.recordId.takeIf { outcome.needsRemoteClassification }
            is IngestionOutcome.Attached ->
                outcome.recordId.takeIf { outcome.needsRemoteClassification }
            else -> null
        }
        if (recordId != null && markRunning(recordId)) {
            applicationScope.launch {
                try {
                    classifyRecord(recordId)
                } finally {
                    synchronized(runningRecordIds) {
                        runningRecordIds.remove(recordId)
                    }
                }
            }
        }
        return outcome
    }

    private suspend fun classifyRecord(recordId: Long) {
        val context = repository.getRemoteClassificationContext(recordId) ?: return
        val settings = settingsRepository.settings.first()
        if (!settings.deepSeekEnabled || settings.deepSeekApiKey.isBlank()) {
            if (repository.isUnclassifiedRecord(recordId)) {
                notificationManager.showClassificationRequired(
                    recordId,
                    "本地规则无法判断，且 DeepSeek 尚未启用，请手动选择分类",
                )
            } else {
                notificationManager.cancelClassificationRequired(recordId)
            }
            return
        }
        when (
            val result = classifier.classify(
                context = context,
                apiKey = settings.deepSeekApiKey,
                model = settings.deepSeekModel,
                thinkingMode = settings.deepSeekThinkingMode,
            )
        ) {
            is RemoteCategoryResult.Classified -> {
                val applied = repository.applyRemoteCategoryIfUnclassified(
                    recordId = recordId,
                    categoryId = result.categoryId,
                    nowMillis = System.currentTimeMillis(),
                )
                if (applied) {
                    notificationManager.cancelClassificationRequired(recordId)
                }
            }
            is RemoteCategoryResult.NeedsManualReview -> {
                if (repository.isUnclassifiedRecord(recordId)) {
                    notificationManager.showClassificationRequired(recordId, result.reason)
                } else {
                    notificationManager.cancelClassificationRequired(recordId)
                }
            }
        }
    }

    private fun markRunning(recordId: Long): Boolean {
        return synchronized(runningRecordIds) {
            runningRecordIds.add(recordId)
        }
    }
}
