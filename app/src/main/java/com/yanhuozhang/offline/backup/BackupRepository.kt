package com.yanhuozhang.offline.backup

import androidx.room.withTransaction
import com.yanhuozhang.offline.data.AppDatabase
import com.yanhuozhang.offline.settings.DeepSeekModel
import com.yanhuozhang.offline.settings.DeepSeekThinkingMode
import java.time.YearMonth

class BackupRepository(
    private val database: AppDatabase,
) {
    suspend fun exportEnvelope(
        createdAtMillis: Long,
        settings: BackupSettings,
    ): BackupEnvelope {
        return database.withTransaction {
            BackupEnvelope(
                createdAtMillis = createdAtMillis,
                ledgerRecords = database.ledgerDao().getAll(),
                transactionEvents = database.eventDao().getAll(),
                categories = database.categoryDao().getAll(),
                personalRules = database.ruleDao().getAll(),
                ignoredFingerprints = database.ignoredFingerprintDao().getAll(),
                settings = settings,
                monthlyAnalysisReports = database.monthlyAnalysisDao().getAll(),
            )
        }
    }

    suspend fun restoreEnvelope(envelope: BackupEnvelope) {
        validate(envelope)
        database.withTransaction {
            database.eventDao().deleteAll()
            database.ruleDao().deleteAll()
            database.ignoredFingerprintDao().deleteAll()
            database.monthlyAnalysisDao().deleteAll()
            database.ledgerDao().deleteAll()
            database.categoryDao().deleteAll()

            database.categoryDao().insertAll(envelope.categories)
            database.ledgerDao().insertAll(envelope.ledgerRecords)
            database.eventDao().insertAll(envelope.transactionEvents)
            for (rule in envelope.personalRules) {
                database.ruleDao().insert(rule)
            }
            database.ignoredFingerprintDao().insertAll(envelope.ignoredFingerprints)
            for (report in envelope.monthlyAnalysisReports) {
                database.monthlyAnalysisDao().upsert(report)
            }
        }
    }

    private fun validate(envelope: BackupEnvelope) {
        if (envelope.schemaVersion !in
            BackupEnvelope.MINIMUM_SUPPORTED_SCHEMA_VERSION..BackupEnvelope.CURRENT_SCHEMA_VERSION
        ) {
            throw BackupException("不支持的备份版本：${envelope.schemaVersion}")
        }
        if (envelope.settings.reminderHour !in 0..23 ||
            envelope.settings.reminderMinute !in 0..59 ||
            envelope.settings.lockTimeoutSeconds < 0 ||
            !DeepSeekModel.isSupported(envelope.settings.deepSeekModel) ||
            !DeepSeekThinkingMode.isSupported(envelope.settings.deepSeekThinkingMode)
        ) {
            throw BackupException("备份设置无效")
        }
        val categoryIds = envelope.categories.map { item -> item.id }.toSet()
        val recordIds = envelope.ledgerRecords.map { item -> item.id }.toSet()
        if (categoryIds.size != envelope.categories.size || recordIds.size != envelope.ledgerRecords.size) {
            throw BackupException("备份包含重复 ID")
        }
        if (envelope.ledgerRecords.any { record ->
                record.amountCents <= 0 || (record.categoryId != null && record.categoryId !in categoryIds)
            }
        ) {
            throw BackupException("账单数据无效")
        }
        if (envelope.transactionEvents.any { event ->
                event.recordId != null && event.recordId !in recordIds
            }
        ) {
            throw BackupException("事件引用了不存在的账单")
        }
        val fingerprints = envelope.transactionEvents.map { event -> event.sourceFingerprint }
        if (fingerprints.distinct().size != fingerprints.size) {
            throw BackupException("备份包含重复事件")
        }
        val reportMonths = envelope.monthlyAnalysisReports.map { report -> report.monthKey }
        if (reportMonths.distinct().size != reportMonths.size ||
            envelope.monthlyAnalysisReports.any { report ->
                runCatching { YearMonth.parse(report.monthKey) }.isFailure ||
                    report.totalExpenseCents <= 0 ||
                    report.summary.isBlank() ||
                    report.inputFingerprint.isBlank() ||
                    !DeepSeekModel.isSupported(report.model) ||
                    !DeepSeekThinkingMode.isSupported(report.thinkingMode)
            }
        ) {
            throw BackupException("月度分析数据无效")
        }
    }
}
