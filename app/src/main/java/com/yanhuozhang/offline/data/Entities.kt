package com.yanhuozhang.offline.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.EventSource
import com.yanhuozhang.offline.core.ObservationKind
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.StatisticsEffect
import com.yanhuozhang.offline.core.TransactionDirection
import kotlinx.serialization.Serializable

@Serializable
enum class LedgerStatus {
    ACTIVE,
    DELETED,
}

@Serializable
enum class EventStatus {
    PENDING,
    LINKED,
    DUPLICATE,
    IGNORED,
}

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name", "accountingType"], unique = true)],
)
@Serializable
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val accountingType: AccountingType,
    val sortOrder: Int,
    val isActive: Boolean,
    val isSystem: Boolean,
    /** 分类身份色，形如 "#EF5350"。为空表示尚未回填，由界面按名称兜底取色。 */
    val colorHex: String? = null,
    /** 分类图标 emoji。为空时界面回落到通用标签图标。 */
    val emoji: String? = null,
)

@Entity(
    tableName = "ledger_records",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index("categoryId"),
        Index("transactionTimeMillis"),
        Index("statisticsTimeMillis"),
        Index(value = ["amountCents", "direction", "paymentChannel", "transactionTimeMillis"]),
        Index("status"),
    ],
)
@Serializable
data class LedgerRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val amountCents: Long,
    val direction: TransactionDirection,
    val accountingType: AccountingType,
    val statisticsEffect: StatisticsEffect,
    val categoryId: Long?,
    val paymentChannel: PaymentChannel,
    val counterparty: String?,
    val transactionTimeMillis: Long,
    val statisticsTimeMillis: Long = transactionTimeMillis,
    val relatedRecordId: Long?,
    val note: String,
    val status: LedgerStatus,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "transaction_events",
    foreignKeys = [
        ForeignKey(
            entity = LedgerRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("recordId"),
        Index(value = ["sourceFingerprint"], unique = true),
        Index(value = ["amountCents", "direction", "transactionTimeMillis"]),
        Index("status"),
    ],
)
@Serializable
data class TransactionEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val recordId: Long?,
    val source: EventSource,
    val sourceEventId: String,
    val sourceFingerprint: String,
    val rawText: String,
    val counterparty: String? = null,
    val amountCents: Long?,
    val direction: TransactionDirection?,
    val kind: ObservationKind,
    val transactionTimeMillis: Long?,
    val observedAtMillis: Long,
    val status: EventStatus,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "personal_rules",
    indices = [Index(value = ["matchText", "direction", "paymentChannel"])],
)
@Serializable
data class PersonalRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val matchText: String,
    val direction: TransactionDirection?,
    val paymentChannel: PaymentChannel?,
    val categoryId: Long?,
    val accountingType: AccountingType,
    val statisticsEffect: StatisticsEffect,
    val isActive: Boolean,
    val createdAtMillis: Long,
)

@Entity(tableName = "ignored_fingerprints")
@Serializable
data class IgnoredFingerprintEntity(
    @PrimaryKey
    val fingerprint: String,
    val createdAtMillis: Long,
)

@Entity(tableName = "monthly_analysis_reports")
@Serializable
data class MonthlyAnalysisReportEntity(
    @PrimaryKey
    val monthKey: String,
    val totalExpenseCents: Long,
    val summary: String,
    val observation1: String?,
    val observation2: String?,
    val observation3: String?,
    val suggestion: String?,
    val lightComment: String?,
    val model: String,
    val thinkingMode: String,
    val inputFingerprint: String,
    val generatedAtMillis: Long,
)
