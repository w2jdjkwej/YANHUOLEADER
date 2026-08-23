package com.yanhuozhang.offline.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.StatisticsEffect
import com.yanhuozhang.offline.core.TransactionDirection
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<CategoryEntity>)

    @Insert
    suspend fun insert(item: CategoryEntity): Long

    @Update
    suspend fun update(item: CategoryEntity)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT * FROM categories ORDER BY accountingType, sortOrder, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE accountingType = :type AND isActive = 1 ORDER BY sortOrder, id")
    fun observeActive(type: AccountingType): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE name = '未分类' AND accountingType = 'EXPENSE' LIMIT 1")
    suspend fun findUncategorizedExpense(): CategoryEntity?

    @Query(
        "SELECT * FROM categories WHERE name = :name AND accountingType = :type AND isActive = 1 LIMIT 1",
    )
    suspend fun findActiveByName(name: String, type: AccountingType): CategoryEntity?

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories ORDER BY accountingType, sortOrder, id")
    suspend fun getAll(): List<CategoryEntity>

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface LedgerDao {
    @Insert
    suspend fun insert(item: LedgerRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<LedgerRecordEntity>)

    @Update
    suspend fun update(item: LedgerRecordEntity)

    @Query("SELECT * FROM ledger_records WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): LedgerRecordEntity?

    @Query("SELECT * FROM ledger_records WHERE status = 'ACTIVE' ORDER BY transactionTimeMillis DESC, id DESC")
    fun observeActive(): Flow<List<LedgerRecordEntity>>

    @Query("SELECT * FROM ledger_records WHERE status = 'DELETED' ORDER BY updatedAtMillis DESC")
    fun observeDeleted(): Flow<List<LedgerRecordEntity>>

    @Query("SELECT * FROM ledger_records WHERE status = 'ACTIVE' ORDER BY transactionTimeMillis DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LedgerRecordEntity>>

    @Query(
        "SELECT * FROM ledger_records " +
            "WHERE status = 'ACTIVE' AND transactionTimeMillis >= :startTimeMillis AND transactionTimeMillis < :endTimeMillis " +
            "ORDER BY transactionTimeMillis DESC, id DESC",
    )
    fun observeBetween(startTimeMillis: Long, endTimeMillis: Long): Flow<List<LedgerRecordEntity>>

    @Query(
        "SELECT * FROM ledger_records " +
            "WHERE status = 'ACTIVE' AND amountCents = :amountCents AND direction = :direction " +
            "AND paymentChannel = :paymentChannel " +
            "AND transactionTimeMillis BETWEEN :startTimeMillis AND :endTimeMillis " +
            "ORDER BY transactionTimeMillis, id",
    )
    suspend fun findMatchCandidates(
        amountCents: Long,
        direction: TransactionDirection,
        paymentChannel: PaymentChannel,
        startTimeMillis: Long,
        endTimeMillis: Long,
    ): List<LedgerRecordEntity>

    @Query("UPDATE ledger_records SET status = 'DELETED', updatedAtMillis = :updatedAtMillis WHERE id = :id")
    suspend fun softDelete(id: Long, updatedAtMillis: Long)

    @Query("UPDATE ledger_records SET status = 'ACTIVE', updatedAtMillis = :updatedAtMillis WHERE id = :id")
    suspend fun restore(id: Long, updatedAtMillis: Long)

    @Query(
        "UPDATE ledger_records SET counterparty = :counterparty, updatedAtMillis = :updatedAtMillis " +
            "WHERE id = :recordId AND (counterparty IS NULL OR counterparty = '')",
    )
    suspend fun fillCounterpartyIfBlank(
        recordId: Long,
        counterparty: String,
        updatedAtMillis: Long,
    ): Int

    @Query(
        "SELECT * FROM ledger_records " +
            "WHERE status = 'ACTIVE' AND categoryId = :categoryId AND counterparty IS NOT NULL",
    )
    suspend fun findActiveByCategoryWithCounterparty(categoryId: Long): List<LedgerRecordEntity>

    @Query(
        "UPDATE ledger_records SET categoryId = :categoryId, accountingType = :accountingType, " +
            "statisticsEffect = :statisticsEffect, updatedAtMillis = :updatedAtMillis WHERE id = :recordId",
    )
    suspend fun updateClassification(
        recordId: Long,
        categoryId: Long,
        accountingType: AccountingType,
        statisticsEffect: StatisticsEffect,
        updatedAtMillis: Long,
    ): Int

    @Query(
        "UPDATE ledger_records SET categoryId = :newCategoryId, updatedAtMillis = :updatedAtMillis " +
            "WHERE id = :recordId AND categoryId = :expectedCategoryId AND status = 'ACTIVE'",
    )
    suspend fun updateCategoryIfCurrent(
        recordId: Long,
        expectedCategoryId: Long,
        newCategoryId: Long,
        updatedAtMillis: Long,
    ): Int

    @Query("SELECT * FROM ledger_records ORDER BY id")
    suspend fun getAll(): List<LedgerRecordEntity>

    @Query(
        "SELECT id FROM ledger_records " +
            "WHERE status = 'DELETED' OR categoryId IS NULL OR categoryId != :uncategorizedId",
    )
    suspend fun findStaleClassificationRecordIds(uncategorizedId: Long): List<Long>

    @Query(
        "SELECT * FROM ledger_records " +
            "WHERE status = 'ACTIVE' AND statisticsTimeMillis >= :startTimeMillis " +
            "AND statisticsTimeMillis < :endTimeMillis ORDER BY statisticsTimeMillis, id",
    )
    suspend fun getActiveBetweenForStatistics(
        startTimeMillis: Long,
        endTimeMillis: Long,
    ): List<LedgerRecordEntity>

    @Query("DELETE FROM ledger_records")
    suspend fun deleteAll()
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: TransactionEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<TransactionEventEntity>)

    @Update
    suspend fun update(item: TransactionEventEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM transaction_events WHERE sourceFingerprint = :fingerprint)")
    suspend fun fingerprintExists(fingerprint: String): Boolean

    @Query("SELECT * FROM transaction_events WHERE sourceFingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): TransactionEventEntity?

    @Query("SELECT * FROM transaction_events WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): TransactionEventEntity?

    @Query("SELECT * FROM transaction_events WHERE recordId = :recordId ORDER BY observedAtMillis, id")
    suspend fun findByRecordId(recordId: Long): List<TransactionEventEntity>

    @Query("SELECT * FROM transaction_events WHERE recordId IN (:recordIds)")
    suspend fun findByRecordIds(recordIds: List<Long>): List<TransactionEventEntity>

    @Query("SELECT * FROM transaction_events WHERE status = 'PENDING' ORDER BY observedAtMillis DESC, id DESC")
    fun observePending(): Flow<List<TransactionEventEntity>>

    @Query("SELECT COUNT(*) FROM transaction_events WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM transaction_events WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    @Query("SELECT * FROM transaction_events ORDER BY id")
    suspend fun getAll(): List<TransactionEventEntity>

    @Query("SELECT id FROM transaction_events WHERE status != 'PENDING'")
    suspend fun findResolvedEventIds(): List<Long>

    @Query("DELETE FROM transaction_events")
    suspend fun deleteAll()
}

@Dao
interface RuleDao {
    @Insert
    suspend fun insert(item: PersonalRuleEntity): Long

    @Update
    suspend fun update(item: PersonalRuleEntity)

    @Query("SELECT * FROM personal_rules ORDER BY isActive DESC, id DESC")
    fun observeAll(): Flow<List<PersonalRuleEntity>>

    @Query("SELECT * FROM personal_rules WHERE isActive = 1 ORDER BY id DESC")
    suspend fun getActive(): List<PersonalRuleEntity>

    @Query(
        "SELECT * FROM personal_rules WHERE matchText = :matchText " +
            "AND ((direction = :direction) OR (direction IS NULL AND :direction IS NULL)) " +
            "AND ((paymentChannel = :paymentChannel) OR (paymentChannel IS NULL AND :paymentChannel IS NULL)) " +
            "ORDER BY id DESC LIMIT 1",
    )
    suspend fun findExact(
        matchText: String,
        direction: TransactionDirection?,
        paymentChannel: PaymentChannel?,
    ): PersonalRuleEntity?

    @Query("SELECT * FROM personal_rules ORDER BY id")
    suspend fun getAll(): List<PersonalRuleEntity>

    @Query("DELETE FROM personal_rules")
    suspend fun deleteAll()
}

@Dao
interface IgnoredFingerprintDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: IgnoredFingerprintEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<IgnoredFingerprintEntity>)

    @Query("SELECT EXISTS(SELECT 1 FROM ignored_fingerprints WHERE fingerprint = :fingerprint)")
    suspend fun exists(fingerprint: String): Boolean

    @Query("SELECT * FROM ignored_fingerprints ORDER BY createdAtMillis")
    suspend fun getAll(): List<IgnoredFingerprintEntity>

    @Query("DELETE FROM ignored_fingerprints")
    suspend fun deleteAll()
}

@Dao
interface MonthlyAnalysisDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MonthlyAnalysisReportEntity)

    @Query("SELECT * FROM monthly_analysis_reports WHERE monthKey = :monthKey LIMIT 1")
    suspend fun findByMonth(monthKey: String): MonthlyAnalysisReportEntity?

    @Query("SELECT * FROM monthly_analysis_reports ORDER BY monthKey DESC LIMIT 1")
    fun observeLatest(): Flow<MonthlyAnalysisReportEntity?>

    @Query("SELECT * FROM monthly_analysis_reports ORDER BY monthKey")
    suspend fun getAll(): List<MonthlyAnalysisReportEntity>

    @Query("DELETE FROM monthly_analysis_reports")
    suspend fun deleteAll()
}
