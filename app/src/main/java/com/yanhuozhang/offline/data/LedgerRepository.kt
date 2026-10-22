package com.yanhuozhang.offline.data

import androidx.room.withTransaction
import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.CounterpartyNormalizer
import com.yanhuozhang.offline.core.DefaultCategories
import com.yanhuozhang.offline.core.EventSource
import com.yanhuozhang.offline.core.LocalCategoryClassifier
import com.yanhuozhang.offline.core.MatchCandidate
import com.yanhuozhang.offline.core.MatchDecision
import com.yanhuozhang.offline.core.MoneyParser
import com.yanhuozhang.offline.core.ObservationKind
import com.yanhuozhang.offline.core.ParsedObservation
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.RawObservation
import com.yanhuozhang.offline.core.SourceFingerprint
import com.yanhuozhang.offline.core.StatisticsEffect
import com.yanhuozhang.offline.core.TransactionDirection
import com.yanhuozhang.offline.core.TransactionMatcher
import com.yanhuozhang.offline.core.WechatNotificationParser
import com.yanhuozhang.offline.core.WechatPaymentPageParser
import com.yanhuozhang.offline.core.WechatRedPacketParser
import com.yanhuozhang.offline.core.WechatSmsParser
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

sealed interface IngestionOutcome {
    data class Created(
        val recordId: Long,
        val needsRemoteClassification: Boolean = false,
    ) : IngestionOutcome

    data class Attached(
        val recordId: Long,
        val needsRemoteClassification: Boolean = false,
    ) : IngestionOutcome

    data class Pending(val eventId: Long, val reason: String) : IngestionOutcome
    data object Duplicate : IngestionOutcome
    data object Ignored : IngestionOutcome
    data object Unsupported : IngestionOutcome
}

data class LedgerInput(
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
)

data class PersonalRuleInput(
    val matchText: String,
    val direction: TransactionDirection?,
    val paymentChannel: PaymentChannel?,
    val categoryId: Long?,
    val accountingType: AccountingType,
    val statisticsEffect: StatisticsEffect,
    val updateHistoricalUncategorized: Boolean = false,
)

data class RemoteClassificationContext(
    val record: LedgerRecordEntity,
    val categories: List<CategoryEntity>,
)

class LedgerRepository(
    private val database: AppDatabase,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val records: Flow<List<LedgerRecordEntity>> = database.ledgerDao().observeActive()
    val deletedRecords: Flow<List<LedgerRecordEntity>> = database.ledgerDao().observeDeleted()
    val recentRecords: Flow<List<LedgerRecordEntity>> = database.ledgerDao().observeRecent(8)
    val pendingEvents: Flow<List<TransactionEventEntity>> = database.eventDao().observePending()
    val pendingCount: Flow<Int> = database.eventDao().observePendingCount()
    val categories: Flow<List<CategoryEntity>> = database.categoryDao().observeAll()
    val rules: Flow<List<PersonalRuleEntity>> = database.ruleDao().observeAll()

    suspend fun findRecord(recordId: Long): LedgerRecordEntity? {
        return database.ledgerDao().findById(recordId)
    }

    suspend fun findEvent(eventId: Long): TransactionEventEntity? {
        return database.eventDao().findById(eventId)
    }

    suspend fun isUnclassifiedRecord(recordId: Long): Boolean {
        val record = database.ledgerDao().findById(recordId) ?: return false
        val uncategorizedId = database.categoryDao().findUncategorizedExpense()?.id ?: return false
        return record.categoryId == uncategorizedId
    }

    suspend fun findStaleClassificationRecordIds(uncategorizedId: Long): List<Long> {
        return database.ledgerDao().findStaleClassificationRecordIds(uncategorizedId)
    }

    suspend fun findResolvedEventIds(): List<Long> {
        return database.eventDao().findResolvedEventIds()
    }

    suspend fun addManual(
        input: LedgerInput,
        nowMillis: Long,
        personalRule: PersonalRuleInput? = null,
    ): Long {
        require(input.amountCents > 0) { "金额必须大于 0" }
        return database.withTransaction {
            val recordId = insertRecord(input, nowMillis)
            val raw = RawObservation(
                source = EventSource.MANUAL,
                sourceEventId = "manual-$recordId",
                title = "手动记账",
                content = input.note,
                observedAtMillis = nowMillis,
            )
            database.eventDao().insert(
                TransactionEventEntity(
                    recordId = recordId,
                    source = EventSource.MANUAL,
                    sourceEventId = raw.sourceEventId,
                    sourceFingerprint = SourceFingerprint.create(raw),
                    rawText = input.note,
                    counterparty = input.counterparty,
                    amountCents = input.amountCents,
                    direction = input.direction,
                    kind = input.accountingType.toObservationKind(),
                    transactionTimeMillis = input.transactionTimeMillis,
                    observedAtMillis = nowMillis,
                    status = EventStatus.LINKED,
                    createdAtMillis = nowMillis,
                ),
            )
            if (personalRule != null) {
                saveRuleInTransaction(personalRule, nowMillis)
            }
            recordId
        }
    }

    suspend fun softDelete(recordId: Long, nowMillis: Long) {
        database.withTransaction {
            val events = database.eventDao().findByRecordId(recordId)
            for (event in events) {
                database.ignoredFingerprintDao().insert(
                    IgnoredFingerprintEntity(
                        fingerprint = event.sourceFingerprint,
                        createdAtMillis = nowMillis,
                    ),
                )
            }
            database.ledgerDao().softDelete(recordId, nowMillis)
        }
    }

    suspend fun restore(recordId: Long, nowMillis: Long) {
        database.ledgerDao().restore(recordId, nowMillis)
    }

    suspend fun updateRecord(
        recordId: Long,
        input: LedgerInput,
        nowMillis: Long,
        personalRule: PersonalRuleInput? = null,
    ) {
        require(input.amountCents > 0) { "金额必须大于 0" }
        database.withTransaction {
            val existing = requireNotNull(database.ledgerDao().findById(recordId)) { "账单不存在" }
            database.ledgerDao().update(
                existing.copy(
                    amountCents = input.amountCents,
                    direction = input.direction,
                    accountingType = input.accountingType,
                    statisticsEffect = input.statisticsEffect,
                    categoryId = input.categoryId,
                    paymentChannel = input.paymentChannel,
                    counterparty = input.counterparty,
                    transactionTimeMillis = input.transactionTimeMillis,
                    statisticsTimeMillis = input.statisticsTimeMillis,
                    relatedRecordId = input.relatedRecordId,
                    note = input.note.trim(),
                    updatedAtMillis = nowMillis,
                ),
            )
            if (personalRule != null) {
                saveRuleInTransaction(personalRule, nowMillis)
            }
        }
    }

    suspend fun confirmPendingAsNewRecord(
        eventId: Long,
        input: LedgerInput,
        nowMillis: Long,
    ): Long {
        require(input.amountCents > 0) { "金额必须大于 0" }
        return database.withTransaction {
            val event = requireNotNull(database.eventDao().findById(eventId)) { "待确认事件不存在" }
            require(event.status == EventStatus.PENDING) { "事件不是待确认状态" }

            val recordId = insertRecord(input, nowMillis)
            database.eventDao().update(
                event.copy(
                    recordId = recordId,
                    status = EventStatus.LINKED,
                ),
            )
            recordId
        }
    }

    suspend fun mergePending(eventId: Long, recordId: Long) {
        database.withTransaction {
            val event = requireNotNull(database.eventDao().findById(eventId)) { "待确认事件不存在" }
            require(event.status == EventStatus.PENDING) { "事件不是待确认状态" }
            val record = requireNotNull(database.ledgerDao().findById(recordId)) { "目标账单不存在" }
            require(record.status == LedgerStatus.ACTIVE) { "目标账单已删除" }
            database.eventDao().update(event.copy(recordId = recordId, status = EventStatus.LINKED))
        }
    }

    suspend fun ignorePending(eventId: Long, nowMillis: Long) {
        database.withTransaction {
            val event = requireNotNull(database.eventDao().findById(eventId)) { "待确认事件不存在" }
            require(event.status == EventStatus.PENDING) { "事件不是待确认状态" }
            database.eventDao().update(event.copy(status = EventStatus.IGNORED))
            database.ignoredFingerprintDao().insert(
                IgnoredFingerprintEntity(event.sourceFingerprint, nowMillis),
            )
        }
    }

    suspend fun addCategory(
        name: String,
        type: AccountingType,
        sortOrder: Int,
        colorHex: String? = null,
        emoji: String? = null,
    ): Long {
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "分类名称不能为空" }
        val preset = DefaultCategories.styleFor(trimmedName, type)
        return database.categoryDao().insert(
            CategoryEntity(
                name = trimmedName,
                accountingType = type,
                sortOrder = sortOrder,
                isActive = true,
                isSystem = false,
                colorHex = colorHex
                    ?: preset?.colorHex
                    ?: DefaultCategories.fallbackColorHex(trimmedName),
                emoji = emoji ?: preset?.emoji ?: DefaultCategories.FALLBACK_EMOJI,
            ),
        )
    }

    suspend fun updateCategory(
        id: Long,
        name: String,
        sortOrder: Int,
        isActive: Boolean,
        colorHex: String? = null,
        emoji: String? = null,
    ) {
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "分类名称不能为空" }
        val existing = requireNotNull(database.categoryDao().findById(id)) { "分类不存在" }
        database.categoryDao().update(
            existing.copy(
                name = trimmedName,
                sortOrder = sortOrder,
                isActive = isActive,
                colorHex = colorHex ?: existing.colorHex,
                emoji = emoji ?: existing.emoji,
            ),
        )
    }

    suspend fun saveRule(input: PersonalRuleInput, nowMillis: Long): Long {
        return database.withTransaction {
            saveRuleInTransaction(input, nowMillis)
        }
    }

    suspend fun getRemoteClassificationContext(recordId: Long): RemoteClassificationContext? {
        val record = database.ledgerDao().findById(recordId) ?: return null
        val uncategorizedId = database.categoryDao().findUncategorizedExpense()?.id ?: return null
        if (record.status != LedgerStatus.ACTIVE || record.categoryId != uncategorizedId) {
            return null
        }
        val categories = database.categoryDao().getAll().filter { category ->
            category.isActive &&
                category.accountingType == AccountingType.EXPENSE &&
                category.name != "未分类"
        }
        if (categories.isEmpty()) {
            return null
        }
        return RemoteClassificationContext(record, categories)
    }

    /**
     * 截图识别后用本地规则补分类：先查个人对象规则，再查本地关键词（关键词只覆盖支出）。
     * 都没命中时返回 null，由调用方决定是否采用模型建议的分类。
     */
    suspend fun suggestCategoryId(
        counterparty: String?,
        note: String,
        accountingType: AccountingType,
        direction: TransactionDirection,
        paymentChannel: PaymentChannel,
    ): Long? {
        val observation = ParsedObservation(
            source = EventSource.MANUAL,
            sourceEventId = "screenshot-suggestion",
            rawText = note,
            amountCents = null,
            direction = direction,
            kind = accountingType.toObservationKind(),
            transactionTimeMillis = null,
            observedAtMillis = 0L,
            canAutoRecord = false,
            counterparty = counterparty,
        )
        val rule = PersonalRuleMatcher.findMatch(
            rules = database.ruleDao().getActive(),
            observation = observation,
            paymentChannel = paymentChannel,
        )
        if (rule?.categoryId != null && rule.accountingType == accountingType) {
            return rule.categoryId
        }
        if (accountingType == AccountingType.EXPENSE) {
            return findLocalCategory(counterparty, note)?.id
        }
        return null
    }

    suspend fun applyRemoteCategoryIfUnclassified(
        recordId: Long,
        categoryId: Long,
        nowMillis: Long,
    ): Boolean {
        return database.withTransaction {
            val category = database.categoryDao().findById(categoryId) ?: return@withTransaction false
            if (!category.isActive || category.accountingType != AccountingType.EXPENSE) {
                return@withTransaction false
            }
            val uncategorizedId = database.categoryDao().findUncategorizedExpense()?.id
                ?: return@withTransaction false
            database.ledgerDao().updateCategoryIfCurrent(
                recordId = recordId,
                expectedCategoryId = uncategorizedId,
                newCategoryId = categoryId,
                updatedAtMillis = nowMillis,
            ) > 0
        }
    }

    suspend fun ingest(raw: RawObservation): IngestionOutcome {
        val parsed = parseObservation(raw) ?: return IngestionOutcome.Unsupported
        val fingerprint = SourceFingerprint.create(raw)

        return database.withTransaction {
            if (database.ignoredFingerprintDao().exists(fingerprint)) {
                return@withTransaction IngestionOutcome.Ignored
            }
            if (database.eventDao().fingerprintExists(fingerprint)) {
                return@withTransaction IngestionOutcome.Duplicate
            }

            DatabaseSeeder.seedDefaultCategories(database.categoryDao())
            val candidates = findCandidates(parsed)
            when (val decision = TransactionMatcher.match(parsed, false, candidates)) {
                MatchDecision.DuplicateEvent -> IngestionOutcome.Duplicate
                MatchDecision.CreateRecord -> createRecordAndEvent(parsed, fingerprint)
                is MatchDecision.AttachToRecord -> attachEvent(parsed, fingerprint, decision.recordId)
                is MatchDecision.NeedConfirmation -> createPendingEvent(parsed, fingerprint, decision.reason)
            }
        }
    }

    private fun parseObservation(raw: RawObservation): ParsedObservation? {
        return when (raw.source) {
            EventSource.WECHAT_NOTIFICATION -> WechatNotificationParser.parseOrNull(raw)
            EventSource.WECHAT_SMS -> WechatSmsParser.parseOrNull(raw, zoneId)
            EventSource.WECHAT_PAYMENT_PAGE ->
                WechatPaymentPageParser.parseRaw(raw) ?: WechatRedPacketParser.parseRaw(raw)
            EventSource.BANK_SMS -> parseBankSms(raw)
            EventSource.MANUAL -> null
        }
    }

    private fun parseBankSms(raw: RawObservation): ParsedObservation {
        val isOutgoing = listOf("支出", "消费", "扣款").any(raw.content::contains)
        return ParsedObservation(
            source = raw.source,
            sourceEventId = raw.sourceEventId,
            rawText = raw.content,
            amountCents = MoneyParser.parseCents(raw.content),
            direction = if (isOutgoing) TransactionDirection.OUT else null,
            kind = ObservationKind.UNKNOWN,
            transactionTimeMillis = null,
            observedAtMillis = raw.observedAtMillis,
            canAutoRecord = false,
        )
    }

    private suspend fun findCandidates(parsed: ParsedObservation): List<MatchCandidate> {
        val amountCents = parsed.amountCents ?: return emptyList()
        val direction = parsed.direction ?: return emptyList()
        val transactionTimeMillis = parsed.transactionTimeMillis ?: return emptyList()

        val records = database.ledgerDao().findMatchCandidates(
            amountCents = amountCents,
            direction = direction,
            paymentChannel = PaymentChannel.WECHAT,
            startTimeMillis = transactionTimeMillis - TransactionMatcher.MATCH_WINDOW_MILLIS,
            endTimeMillis = transactionTimeMillis + TransactionMatcher.MATCH_WINDOW_MILLIS,
        )
        val recordIds = records.map { record -> record.id }
        val eventsByRecordId = if (recordIds.isEmpty()) {
            emptyMap()
        } else {
            database.eventDao().findByRecordIds(recordIds)
                .filter { event -> event.recordId != null }
                .groupBy { event -> requireNotNull(event.recordId) }
        }

        return records.map { record ->
            val events = eventsByRecordId[record.id].orEmpty()
            MatchCandidate(
                recordId = record.id,
                amountCents = record.amountCents,
                direction = record.direction,
                transactionTimeMillis = record.transactionTimeMillis,
                paymentChannel = record.paymentChannel,
                observedSources = events.map { event -> event.source }.toSet(),
                counterparty = record.counterparty,
                incompletePaymentPageEventIds = events.asSequence()
                    .filter { event -> event.source == EventSource.WECHAT_PAYMENT_PAGE }
                    .filter { event -> event.counterparty.isNullOrBlank() }
                    .map { event -> event.sourceEventId }
                    .toSet(),
            )
        }
    }

    private suspend fun createRecordAndEvent(
        parsed: ParsedObservation,
        fingerprint: String,
    ): IngestionOutcome.Created {
        val amountCents = requireNotNull(parsed.amountCents)
        val transactionTimeMillis = requireNotNull(parsed.transactionTimeMillis)
        val personalRule = PersonalRuleMatcher.findMatch(
            rules = database.ruleDao().getActive(),
            observation = parsed,
            paymentChannel = PaymentChannel.WECHAT,
        )
        val localCategory = if (personalRule == null) {
            findLocalCategory(parsed.counterparty, parsed.rawText)
        } else {
            null
        }
        val categoryId = personalRule?.categoryId
            ?: localCategory?.id
            ?: database.categoryDao().findUncategorizedExpense()?.id
        val needsRemoteClassification = personalRule == null && localCategory == null
        val recordId = database.ledgerDao().insert(
            LedgerRecordEntity(
                amountCents = amountCents,
                direction = TransactionDirection.OUT,
                accountingType = personalRule?.accountingType ?: AccountingType.EXPENSE,
                statisticsEffect = personalRule?.statisticsEffect ?: StatisticsEffect.ADD_EXPENSE,
                categoryId = categoryId,
                paymentChannel = PaymentChannel.WECHAT,
                counterparty = parsed.counterparty,
                transactionTimeMillis = transactionTimeMillis,
                relatedRecordId = null,
                note = "",
                status = LedgerStatus.ACTIVE,
                createdAtMillis = parsed.observedAtMillis,
                updatedAtMillis = parsed.observedAtMillis,
            ),
        )
        insertEvent(parsed, fingerprint, recordId, EventStatus.LINKED)
        return IngestionOutcome.Created(recordId, needsRemoteClassification)
    }

    private suspend fun attachEvent(
        parsed: ParsedObservation,
        fingerprint: String,
        recordId: Long,
    ): IngestionOutcome.Attached {
        insertEvent(parsed, fingerprint, recordId, EventStatus.LINKED)
        parsed.counterparty?.let { counterparty ->
            database.ledgerDao().fillCounterpartyIfBlank(
                recordId = recordId,
                counterparty = counterparty,
                updatedAtMillis = parsed.observedAtMillis,
            )
        }
        val record = database.ledgerDao().findById(recordId)
        val uncategorizedId = database.categoryDao().findUncategorizedExpense()?.id
        val isUnclassified = record != null && record.categoryId == uncategorizedId
        val personalRule = if (isUnclassified) {
            PersonalRuleMatcher.findMatch(
                rules = database.ruleDao().getActive(),
                observation = parsed,
                paymentChannel = requireNotNull(record).paymentChannel,
            )
        } else {
            null
        }
        val localCategory = if (isUnclassified && personalRule == null) {
            findLocalCategory(parsed.counterparty, parsed.rawText)
        } else {
            null
        }
        val matchedCategoryId = personalRule?.categoryId ?: localCategory?.id
        if (record != null && matchedCategoryId != null) {
            database.ledgerDao().updateClassification(
                recordId = record.id,
                categoryId = matchedCategoryId,
                accountingType = personalRule?.accountingType ?: AccountingType.EXPENSE,
                statisticsEffect = personalRule?.statisticsEffect ?: StatisticsEffect.ADD_EXPENSE,
                updatedAtMillis = parsed.observedAtMillis,
            )
        }
        return IngestionOutcome.Attached(
            recordId = recordId,
            needsRemoteClassification = isUnclassified && matchedCategoryId == null,
        )
    }

    private suspend fun createPendingEvent(
        parsed: ParsedObservation,
        fingerprint: String,
        reason: String,
    ): IngestionOutcome.Pending {
        val eventId = insertEvent(parsed, fingerprint, null, EventStatus.PENDING)
        return IngestionOutcome.Pending(eventId, reason)
    }

    private suspend fun insertEvent(
        parsed: ParsedObservation,
        fingerprint: String,
        recordId: Long?,
        status: EventStatus,
    ): Long {
        return database.eventDao().insert(
            TransactionEventEntity(
                recordId = recordId,
                source = parsed.source,
                sourceEventId = parsed.sourceEventId,
                sourceFingerprint = fingerprint,
                rawText = parsed.rawText,
                counterparty = parsed.counterparty,
                amountCents = parsed.amountCents,
                direction = parsed.direction,
                kind = parsed.kind,
                transactionTimeMillis = parsed.transactionTimeMillis,
                observedAtMillis = parsed.observedAtMillis,
                status = status,
                createdAtMillis = parsed.observedAtMillis,
            ),
        )
    }

    private suspend fun insertRecord(input: LedgerInput, nowMillis: Long): Long {
        return database.ledgerDao().insert(
            LedgerRecordEntity(
                amountCents = input.amountCents,
                direction = input.direction,
                accountingType = input.accountingType,
                statisticsEffect = input.statisticsEffect,
                categoryId = input.categoryId,
                paymentChannel = input.paymentChannel,
                counterparty = input.counterparty,
                transactionTimeMillis = input.transactionTimeMillis,
                statisticsTimeMillis = input.statisticsTimeMillis,
                relatedRecordId = input.relatedRecordId,
                note = input.note.trim(),
                status = LedgerStatus.ACTIVE,
                createdAtMillis = nowMillis,
                updatedAtMillis = nowMillis,
            ),
        )
    }

    private suspend fun saveRuleInTransaction(input: PersonalRuleInput, nowMillis: Long): Long {
        val matchText = CounterpartyNormalizer.normalize(input.matchText)
        require(matchText.isNotEmpty()) { "规则匹配文本不能为空" }
        val existing = database.ruleDao().findExact(
            matchText = matchText,
            direction = input.direction,
            paymentChannel = input.paymentChannel,
        )
        val ruleId = if (existing == null) {
            database.ruleDao().insert(
                PersonalRuleEntity(
                    matchText = matchText,
                    direction = input.direction,
                    paymentChannel = input.paymentChannel,
                    categoryId = input.categoryId,
                    accountingType = input.accountingType,
                    statisticsEffect = input.statisticsEffect,
                    isActive = true,
                    createdAtMillis = nowMillis,
                ),
            )
        } else {
            database.ruleDao().update(
                existing.copy(
                    categoryId = input.categoryId,
                    accountingType = input.accountingType,
                    statisticsEffect = input.statisticsEffect,
                    isActive = true,
                ),
            )
            existing.id
        }

        if (input.updateHistoricalUncategorized && input.categoryId != null) {
            updateHistoricalUncategorized(
                normalizedCounterparty = matchText,
                categoryId = input.categoryId,
                accountingType = input.accountingType,
                statisticsEffect = input.statisticsEffect,
                nowMillis = nowMillis,
            )
        }
        return ruleId
    }

    private suspend fun updateHistoricalUncategorized(
        normalizedCounterparty: String,
        categoryId: Long,
        accountingType: AccountingType,
        statisticsEffect: StatisticsEffect,
        nowMillis: Long,
    ) {
        val uncategorizedId = database.categoryDao().findUncategorizedExpense()?.id ?: return
        val records = database.ledgerDao().findActiveByCategoryWithCounterparty(uncategorizedId)
        for (record in records) {
            if (CounterpartyNormalizer.normalize(record.counterparty) == normalizedCounterparty) {
                database.ledgerDao().updateClassification(
                    recordId = record.id,
                    categoryId = categoryId,
                    accountingType = accountingType,
                    statisticsEffect = statisticsEffect,
                    updatedAtMillis = nowMillis,
                )
            }
        }
    }

    private suspend fun findLocalCategory(counterparty: String?, note: String?): CategoryEntity? {
        val match = LocalCategoryClassifier.classify(counterparty, note) ?: return null
        return database.categoryDao().findActiveByName(match.categoryName, AccountingType.EXPENSE)
    }

    private fun AccountingType.toObservationKind(): ObservationKind {
        return when (this) {
            AccountingType.TRANSFER,
            AccountingType.FLOW,
            -> ObservationKind.TRANSFER
            AccountingType.REFUND -> ObservationKind.REFUND
            AccountingType.INCOME,
            AccountingType.EXPENSE,
            -> ObservationKind.PAYMENT
        }
    }
}
