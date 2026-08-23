package com.yanhuozhang.offline.backup

import com.yanhuozhang.offline.data.CategoryEntity
import com.yanhuozhang.offline.data.IgnoredFingerprintEntity
import com.yanhuozhang.offline.data.LedgerRecordEntity
import com.yanhuozhang.offline.data.MonthlyAnalysisReportEntity
import com.yanhuozhang.offline.data.PersonalRuleEntity
import com.yanhuozhang.offline.data.TransactionEventEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupSettings(
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    val appLockEnabled: Boolean = true,
    val lockTimeoutSeconds: Int = 60,
    val deepSeekEnabled: Boolean = false,
    val deepSeekApiKey: String = "",
    val deepSeekModel: String = "deepseek-v4-flash",
    val deepSeekThinkingMode: String = "disabled",
)

@Serializable
data class BackupEnvelope(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val createdAtMillis: Long,
    val ledgerRecords: List<LedgerRecordEntity>,
    val transactionEvents: List<TransactionEventEntity>,
    val categories: List<CategoryEntity>,
    val personalRules: List<PersonalRuleEntity>,
    val ignoredFingerprints: List<IgnoredFingerprintEntity>,
    val settings: BackupSettings,
    val monthlyAnalysisReports: List<MonthlyAnalysisReportEntity> = emptyList(),
) {
    companion object {
        const val MINIMUM_SUPPORTED_SCHEMA_VERSION = 1
        const val CURRENT_SCHEMA_VERSION = 4
    }
}

object BackupCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(envelope: BackupEnvelope): ByteArray {
        return json.encodeToString(BackupEnvelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): BackupEnvelope {
        return try {
            json.decodeFromString(BackupEnvelope.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (error: RuntimeException) {
            throw BackupException("备份内容无法解析", error)
        }
    }
}
