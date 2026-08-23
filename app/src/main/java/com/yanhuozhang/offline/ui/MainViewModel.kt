package com.yanhuozhang.offline.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanhuozhang.offline.YanHuoApplication
import com.yanhuozhang.offline.analysis.MonthlyAnalysisGenerationResult
import com.yanhuozhang.offline.backup.BackupCodec
import com.yanhuozhang.offline.backup.BackupCrypto
import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.MoneyParser
import com.yanhuozhang.offline.data.CategoryEntity
import com.yanhuozhang.offline.data.LedgerInput
import com.yanhuozhang.offline.data.LedgerRecordEntity
import com.yanhuozhang.offline.data.MonthlyAnalysisReportEntity
import com.yanhuozhang.offline.data.PersonalRuleInput
import com.yanhuozhang.offline.data.PersonalRuleEntity
import com.yanhuozhang.offline.data.TransactionEventEntity
import com.yanhuozhang.offline.settings.AppSettings
import com.yanhuozhang.offline.settings.DeepSeekModel
import com.yanhuozhang.offline.settings.DeepSeekThinkingMode
import com.yanhuozhang.offline.system.ReminderScheduler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.YearMonth
import java.time.ZoneId

data class MainUiState(
    val records: List<LedgerRecordEntity> = emptyList(),
    val deletedRecords: List<LedgerRecordEntity> = emptyList(),
    val pendingEvents: List<TransactionEventEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val rules: List<PersonalRuleEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val dashboard: DashboardSummary = DashboardSummary(0, 0, 0, 0),
    val trend: List<MonthlyTrendPoint> = emptyList(),
    val categoryRanking: List<CategoryExpense> = emptyList(),
    val unclassifiedCount: Int = 0,
    val latestMonthlyAnalysis: MonthlyAnalysisReportEntity? = null,
    val monthlyAnalysisRunning: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as YanHuoApplication
    private val repository = app.container.ledgerRepository
    private val settingsRepository = app.container.settingsRepository
    private val backupRepository = app.container.backupRepository
    private val monthlyAnalysisRepository = app.container.monthlyAnalysisRepository
    private val monthlyAnalysisRunning = MutableStateFlow(false)

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private data class BusinessState(
        val records: List<LedgerRecordEntity>,
        val deleted: List<LedgerRecordEntity>,
        val pending: List<TransactionEventEntity>,
        val categories: List<CategoryEntity>,
        val rules: List<PersonalRuleEntity>,
    )

    private val businessState = combine(
        repository.records,
        repository.deletedRecords,
        repository.pendingEvents,
        repository.categories,
        repository.rules,
    ) { records, deleted, pending, categories, rules ->
        BusinessState(records, deleted, pending, categories, rules)
    }

    private data class MonthlyAnalysisState(
        val report: MonthlyAnalysisReportEntity?,
        val running: Boolean,
    )

    private val monthlyAnalysisState = combine(
        monthlyAnalysisRepository.latestReport,
        monthlyAnalysisRunning,
    ) { report, running ->
        MonthlyAnalysisState(report, running)
    }

    val uiState: StateFlow<MainUiState> = combine(
        businessState,
        settingsRepository.settings,
        monthlyAnalysisState,
    ) { business, settings, analysis ->
        val now = ZonedDateTime.now()
        val unclassifiedId = business.categories.firstOrNull { category ->
            category.name == "未分类" && category.accountingType == AccountingType.EXPENSE
        }?.id
        MainUiState(
            records = business.records,
            deletedRecords = business.deleted,
            pendingEvents = business.pending,
            categories = business.categories,
            rules = business.rules,
            settings = settings,
            dashboard = AnalyticsCalculator.dashboard(business.records, business.pending.size, now),
            trend = AnalyticsCalculator.sixMonthTrend(business.records, now),
            categoryRanking = AnalyticsCalculator.categoryRanking(business.records, now),
            unclassifiedCount = business.records.count { record ->
                unclassifiedId != null && record.categoryId == unclassifiedId
            },
            latestMonthlyAnalysis = analysis.report,
            monthlyAnalysisRunning = analysis.running,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    fun addManual(input: LedgerInput, rule: PersonalRuleInput? = null) {
        launchOperation("账单已保存") {
            repository.addManual(input, System.currentTimeMillis(), rule)
        }
    }

    fun updateRecord(recordId: Long, input: LedgerInput, rule: PersonalRuleInput? = null) {
        launchOperation("账单已更新") {
            repository.updateRecord(recordId, input, System.currentTimeMillis(), rule)
            app.container.notificationManager.cancelClassificationRequired(recordId)
        }
    }

    fun deleteRecord(recordId: Long) {
        launchOperation("账单已移入已删除") {
            repository.softDelete(recordId, System.currentTimeMillis())
        }
    }

    fun restoreRecord(recordId: Long) {
        launchOperation("账单已恢复") {
            repository.restore(recordId, System.currentTimeMillis())
        }
    }

    fun confirmPending(eventId: Long, input: LedgerInput, rule: PersonalRuleInput?) {
        launchOperation("待确认账单已处理") {
            repository.confirmPendingAsNewRecord(eventId, input, System.currentTimeMillis())
            if (rule != null) {
                repository.saveRule(rule, System.currentTimeMillis())
            }
            app.container.notificationManager.cancelPending(eventId)
        }
    }

    fun mergePending(eventId: Long, recordId: Long) {
        launchOperation("观察事件已合并") {
            repository.mergePending(eventId, recordId)
            app.container.notificationManager.cancelPending(eventId)
        }
    }

    fun ignorePending(eventId: Long) {
        launchOperation("该事件已忽略") {
            repository.ignorePending(eventId, System.currentTimeMillis())
            app.container.notificationManager.cancelPending(eventId)
        }
    }

    fun addCategory(
        name: String,
        type: AccountingType,
        sortOrder: Int,
        colorHex: String? = null,
        emoji: String? = null,
    ) {
        launchOperation("分类已添加") {
            repository.addCategory(name, type, sortOrder, colorHex, emoji)
        }
    }

    fun updateCategory(
        category: CategoryEntity,
        name: String,
        sortOrder: Int,
        active: Boolean,
        colorHex: String? = null,
        emoji: String? = null,
    ) {
        launchOperation("分类已更新") {
            repository.updateCategory(category.id, name, sortOrder, active, colorHex, emoji)
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepository.setOnboardingCompleted(true)
        }
    }

    fun updateReminder(enabled: Boolean, hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsRepository.setReminder(enabled, hour, minute)
            ReminderScheduler.schedule(app, enabled, hour, minute)
            messages.emit("提醒设置已保存")
        }
    }

    fun updateAppLock(enabled: Boolean, timeoutSeconds: Int) {
        viewModelScope.launch {
            settingsRepository.setAppLock(enabled, timeoutSeconds)
            messages.emit("应用锁设置已保存")
        }
    }

    fun updateDeepSeek(
        enabled: Boolean,
        apiKey: String,
        model: DeepSeekModel,
        thinkingMode: DeepSeekThinkingMode,
    ) {
        launchOperation("DeepSeek 设置已保存") {
            settingsRepository.setDeepSeek(enabled, apiKey, model, thinkingMode)
        }
    }

    fun analyzeCurrentMonth() {
        if (monthlyAnalysisRunning.value) {
            return
        }
        viewModelScope.launch {
            monthlyAnalysisRunning.value = true
            try {
                val month = YearMonth.now(ZoneId.of("Asia/Shanghai"))
                when (
                    val result = monthlyAnalysisRepository.generate(
                        month = month,
                        force = true,
                    )
                ) {
                    is MonthlyAnalysisGenerationResult.Generated ->
                        messages.emit("本月账单分析已生成")
                    is MonthlyAnalysisGenerationResult.AlreadyCurrent ->
                        messages.emit("本月账单分析已是最新")
                    MonthlyAnalysisGenerationResult.DeepSeekUnavailable ->
                        messages.emit("请先在设置中启用 DeepSeek 并填写 API Key")
                    MonthlyAnalysisGenerationResult.NoExpense ->
                        messages.emit("本月还没有可分析的支出")
                    is MonthlyAnalysisGenerationResult.Failed ->
                        messages.emit(result.reason)
                }
            } catch (error: Exception) {
                messages.emit(error.message ?: "月度账单分析失败")
            } finally {
                monthlyAnalysisRunning.value = false
            }
        }
    }

    suspend fun createEncryptedBackup(password: CharArray): ByteArray {
        val settings = settingsRepository.settings.first()
        val envelope = backupRepository.exportEnvelope(
            createdAtMillis = System.currentTimeMillis(),
            settings = settings.toBackupSettings(),
        )
        return BackupCrypto.encrypt(BackupCodec.encode(envelope), password)
    }

    suspend fun restoreEncryptedBackup(bytes: ByteArray, password: CharArray) {
        val envelope = BackupCodec.decode(BackupCrypto.decrypt(bytes, password))
        backupRepository.restoreEnvelope(envelope)
        settingsRepository.restoreBackupSettings(envelope.settings)
    }

    fun parseAmount(text: String): Long? = MoneyParser.parseUserInputCents(text)

    private fun launchOperation(successMessage: String, operation: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                operation()
                messages.emit(successMessage)
            } catch (error: Exception) {
                messages.emit(error.message ?: "操作失败")
            }
        }
    }
}
