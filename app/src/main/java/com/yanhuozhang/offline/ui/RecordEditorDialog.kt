package com.yanhuozhang.offline.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.StatisticsEffect
import com.yanhuozhang.offline.core.TransactionDirection
import com.yanhuozhang.offline.data.CategoryEntity
import com.yanhuozhang.offline.data.LedgerInput
import com.yanhuozhang.offline.data.LedgerRecordEntity
import com.yanhuozhang.offline.data.PersonalRuleInput
import com.yanhuozhang.offline.data.TransactionEventEntity
import com.yanhuozhang.offline.recognition.PossibleDuplicateFinder
import com.yanhuozhang.offline.recognition.ScreenshotRecognitionResult
import com.yanhuozhang.offline.recognition.ScreenshotRecognitionState
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordEditorDialog(
    existing: LedgerRecordEntity?,
    pendingEvent: TransactionEventEntity?,
    categories: List<CategoryEntity>,
    records: List<LedgerRecordEntity>,
    parseAmount: (String) -> Long?,
    onDismiss: () -> Unit,
    onSave: (LedgerInput, PersonalRuleInput?) -> Unit,
    screenshotRecognition: ScreenshotRecognitionState = ScreenshotRecognitionState.Idle,
    screenshotRecognitionAvailable: Boolean = false,
    /** 为 null 时不显示“从截图识别”；只有新建账单时才传入。 */
    onScreenshotPicked: ((Uri) -> Unit)? = null,
    onCancelScreenshotRecognition: () -> Unit = {},
    onScreenshotRecognitionConsumed: (Long) -> Unit = {},
) {
    val initialAmount = existing?.amountCents ?: pendingEvent?.amountCents
    var amountText by remember { mutableStateOf(initialAmount?.toYuanText().orEmpty()) }
    var accountingType by remember {
        mutableStateOf(existing?.accountingType ?: pendingEvent?.defaultAccountingType() ?: AccountingType.EXPENSE)
    }
    var direction by remember {
        mutableStateOf(existing?.direction ?: pendingEvent?.direction ?: TransactionDirection.OUT)
    }
    var categoryId by remember { mutableStateOf(existing?.categoryId) }
    var paymentChannel by remember {
        mutableStateOf(existing?.paymentChannel ?: pendingEvent?.defaultChannel() ?: PaymentChannel.CASH)
    }
    var counterparty by remember {
        mutableStateOf(existing?.counterparty ?: pendingEvent?.counterparty.orEmpty())
    }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var transactionTimeMillis by remember {
        mutableStateOf(
            existing?.transactionTimeMillis
                ?: pendingEvent?.transactionTimeMillis
                ?: System.currentTimeMillis(),
        )
    }
    var includeTransferInStats by remember {
        mutableStateOf(existing?.statisticsEffect != StatisticsEffect.NONE)
    }
    var adjustOriginalMonth by remember {
        mutableStateOf(
            existing?.accountingType == AccountingType.REFUND &&
                existing.statisticsTimeMillis != existing.transactionTimeMillis,
        )
    }
    var relatedRecordId by remember { mutableStateOf(existing?.relatedRecordId) }
    var saveRule by remember { mutableStateOf(true) }
    var updateHistoricalUncategorized by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // 截图识别回填后的提示；用户改动对应字段后提示随之消失。
    val showScreenshotEntry = onScreenshotPicked != null && existing == null && pendingEvent == null
    var screenshotNotice by remember { mutableStateOf<ScreenshotNotice?>(null) }
    var filledFromScreenshot by remember { mutableStateOf(false) }
    var screenshotAmountMissing by remember { mutableStateOf(false) }
    var screenshotTimeMissing by remember { mutableStateOf(false) }
    var counterpartyInferred by remember { mutableStateOf(false) }
    var counterpartyBasis by remember { mutableStateOf<String?>(null) }
    val screenshotPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            screenshotNotice = null
            onScreenshotPicked?.invoke(uri)
        }
    }

    LaunchedEffect(screenshotRecognition) {
        if (!showScreenshotEntry) return@LaunchedEffect
        val finished = screenshotRecognition as? ScreenshotRecognitionState.Finished
            ?: return@LaunchedEffect
        when (val result = finished.result) {
            is ScreenshotRecognitionResult.Recognized -> {
                val recognized = result.transaction
                accountingType = recognized.accountingType
                direction = recognized.direction
                categoryId = recognized.categoryId
                paymentChannel = recognized.paymentChannel
                // 截图里缺的字段保留表单原值，不清空用户已经填的内容。
                recognized.amountCents?.let { cents -> amountText = cents.toYuanText() }
                recognized.counterparty?.let { text -> counterparty = text }
                recognized.note.takeIf { text -> text.isNotEmpty() }?.let { text -> note = text }
                recognized.transactionTimeMillis?.let { time -> transactionTimeMillis = time }
                includeTransferInStats = true
                adjustOriginalMonth = false
                relatedRecordId = null
                // 推测出的交易对象可能不对，默认不据此生成个人规则，用户确认后可自己勾选。
                saveRule = !recognized.counterpartyInferred
                filledFromScreenshot = true
                screenshotAmountMissing = recognized.amountCents == null
                screenshotTimeMissing = recognized.transactionTimeMillis == null
                counterpartyInferred = recognized.counterpartyInferred
                counterpartyBasis = recognized.counterpartyBasis
                errorText = null
                screenshotNotice = ScreenshotNotice.Filled(recognized.warnings)
            }
            is ScreenshotRecognitionResult.Failed -> {
                screenshotNotice = ScreenshotNotice.Failed(result.reason)
            }
        }
        onScreenshotRecognitionConsumed(finished.requestId)
    }

    val categoryType = when (accountingType) {
        AccountingType.INCOME -> AccountingType.INCOME
        AccountingType.TRANSFER,
        AccountingType.FLOW,
        -> AccountingType.TRANSFER
        AccountingType.EXPENSE,
        AccountingType.REFUND,
        -> AccountingType.EXPENSE
    }
    val availableCategories = categories.filter { category ->
        category.accountingType == categoryType && category.isActive
    }
    val selectedCategory = categories.firstOrNull { category -> category.id == categoryId }
    val selectedRelatedRecord = records.firstOrNull { record -> record.id == relatedRecordId }
    val canSavePersonalRule =
        accountingType == AccountingType.EXPENSE &&
            counterparty.isNotBlank() &&
            selectedCategory != null &&
            selectedCategory.name != "未分类"
    val duplicateAmountCents = if (filledFromScreenshot) parseAmount(amountText) else null
    val possibleDuplicates = remember(
        records,
        duplicateAmountCents,
        direction,
        transactionTimeMillis,
        screenshotTimeMissing,
    ) {
        if (duplicateAmountCents == null) {
            emptyList()
        } else {
            PossibleDuplicateFinder.find(
                records = records,
                amountCents = duplicateAmountCents,
                direction = direction,
                transactionTimeMillis = transactionTimeMillis,
                windowMillis = if (screenshotTimeMissing) {
                    PossibleDuplicateFinder.UNKNOWN_TIME_WINDOW_MILLIS
                } else {
                    PossibleDuplicateFinder.KNOWN_TIME_WINDOW_MILLIS
                },
            )
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = null,
        // 自己接管 inset：没有拖拽把手时弹窗会一直顶到屏幕最上沿，标题会被状态栏和灵动岛压住。
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // union 取每条边的较大值：键盘弹起时按 IME 高度让位，否则按系统栏。
                // 只用 systemBars 的话，键盘会盖住下半张表单，滚不到保存按钮。
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = YanHuoDimens.ScreenPadding)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = if (existing == null) "记录一笔" else "编辑账单",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = "填写金额和交易信息",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "取消")
                    }
                }
            }
            if (showScreenshotEntry) {
                ScreenshotRecognitionEntry(
                    available = screenshotRecognitionAvailable,
                    runningRequestId = (screenshotRecognition as? ScreenshotRecognitionState.Running)?.requestId,
                    onPick = {
                        screenshotPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onCancel = onCancelScreenshotRecognition,
                )
            }
            screenshotNotice?.let { notice -> ScreenshotNoticePanel(notice) }
            YanHuoPanel(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { value ->
                        amountText = value
                        errorText = null
                        screenshotAmountMissing = false
                    },
                    label = { Text("金额（元）") },
                    supportingText = if (screenshotAmountMissing) {
                        { Text("截图里没有找到金额，请手动填写") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    textStyle = MaterialTheme.typography.headlineMedium,
                    shape = RoundedCornerShape(18.dp),
                )
            }
            EditorSection(title = "交易类型") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (candidate in AccountingType.entries) {
                        FilterChip(
                            selected = accountingType == candidate,
                            onClick = {
                                accountingType = candidate
                                direction = if (candidate == AccountingType.INCOME) {
                                    TransactionDirection.IN
                                } else {
                                    TransactionDirection.OUT
                                }
                                categoryId = null
                            },
                            label = { Text(accountingTypeLabel(candidate)) },
                        )
                    }
                }
                if (accountingType == AccountingType.TRANSFER || accountingType == AccountingType.FLOW) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = direction == TransactionDirection.OUT,
                            onClick = { direction = TransactionDirection.OUT },
                            label = { Text("转出") },
                        )
                        FilterChip(
                            selected = direction == TransactionDirection.IN,
                            onClick = { direction = TransactionDirection.IN },
                            label = { Text("转入") },
                        )
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(
                            checked = includeTransferInStats,
                            onCheckedChange = { includeTransferInStats = it },
                        )
                        Text("计入收入或支出统计")
                    }
                }
            }
            EditorSection(title = "分类与支付") {
                Text("分类", style = MaterialTheme.typography.labelLarge)
                CategoryPicker(
                    categories = availableCategories,
                    selectedId = categoryId,
                    onSelect = { id -> categoryId = id },
                )
                Text("支付方式", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (candidate in PaymentChannel.entries) {
                        FilterChip(
                            selected = paymentChannel == candidate,
                            onClick = { paymentChannel = candidate },
                            label = { Text(paymentChannelLabel(candidate)) },
                        )
                    }
                }
            }
            EditorSection(title = "时间与说明") {
                DateTimeSelector(
                    timeMillis = transactionTimeMillis,
                    onTimeChanged = { time ->
                        transactionTimeMillis = time
                        screenshotTimeMissing = false
                    },
                )
                if (screenshotTimeMissing) {
                    Text(
                        text = "截图里没有可用的交易时间，已使用当前时间，请核对",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (accountingType == AccountingType.REFUND) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(
                            checked = adjustOriginalMonth,
                            onCheckedChange = { adjustOriginalMonth = it },
                        )
                        Text("调整原消费月份（否则冲减退款当月）")
                    }
                    if (adjustOriginalMonth) {
                        SelectionDropdown(
                            label = "原消费账单",
                            selectedText = selectedRelatedRecord?.let(::recordOptionText) ?: "请选择",
                            items = records.filter { record -> record.statisticsEffect == StatisticsEffect.ADD_EXPENSE },
                            itemText = ::recordOptionText,
                            onSelected = { record -> relatedRecordId = record.id },
                        )
                    }
                }
                OutlinedTextField(
                    value = counterparty,
                    onValueChange = { text ->
                        counterparty = text
                        counterpartyInferred = false
                    },
                    label = { Text(if (counterpartyInferred) "交易对象（推测）" else "交易对象（可选）") },
                    supportingText = if (counterpartyInferred) {
                        { Text("截图上没有写明，${counterpartyBasis ?: "由截图内容推测"}") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (canSavePersonalRule) {
                EditorSection(title = "自动归类规则") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = saveRule, onCheckedChange = { saveRule = it })
                        Text("记住此交易对象，以后自动归为“${selectedCategory?.name.orEmpty()}”")
                    }
                    if (saveRule) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = updateHistoricalUncategorized,
                                onCheckedChange = { updateHistoricalUncategorized = it },
                            )
                            Text("同时修改历史相同对象的未分类账单")
                        }
                    }
                }
            }
            if (possibleDuplicates.isNotEmpty()) {
                PossibleDuplicatePanel(possibleDuplicates)
            }
            errorText?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(0.35f)) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        val amountCents = parseAmount(amountText)
                        if (amountCents == null) {
                            errorText = "请输入大于 0 且最多两位小数的金额"
                            return@Button
                        }
                        if (adjustOriginalMonth && selectedRelatedRecord == null) {
                            errorText = "请选择原消费账单"
                            return@Button
                        }
                        val effect = statisticsEffect(accountingType, direction, includeTransferInStats)
                        val statisticsTime = if (accountingType == AccountingType.REFUND && adjustOriginalMonth) {
                            requireNotNull(selectedRelatedRecord).statisticsTimeMillis
                        } else {
                            transactionTimeMillis
                        }
                        val input = LedgerInput(
                            amountCents = amountCents,
                            direction = direction,
                            accountingType = accountingType,
                            statisticsEffect = effect,
                            categoryId = categoryId,
                            paymentChannel = paymentChannel,
                            counterparty = counterparty.ifBlank { null },
                            transactionTimeMillis = transactionTimeMillis,
                            statisticsTimeMillis = statisticsTime,
                            relatedRecordId = if (adjustOriginalMonth) relatedRecordId else null,
                            note = note,
                        )
                        val rule = if (saveRule && canSavePersonalRule) {
                            PersonalRuleInput(
                                matchText = counterparty,
                                direction = direction,
                                paymentChannel = null,
                                categoryId = categoryId,
                                accountingType = accountingType,
                                statisticsEffect = effect,
                                updateHistoricalUncategorized = updateHistoricalUncategorized,
                            )
                        } else {
                            null
                        }
                        onSave(input, rule)
                    },
                    modifier = Modifier.weight(0.65f).height(52.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("保存")
                }
            }
        }
    }
}

@Composable
private fun EditorSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
        YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun <T> SelectionDropdown(
    label: String,
    selectedText: String,
    items: List<T>,
    itemText: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selectedText)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (item in items) {
                DropdownMenuItem(
                    text = { Text(itemText(item)) },
                    onClick = { onSelected(item); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun DateTimeSelector(timeMillis: Long, onTimeChanged: (Long) -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(timeMillis).atZone(zone)
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }
    OutlinedButton(
        onClick = {
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    val selectedDate = dateTime.withYear(year).withMonth(month + 1).withDayOfMonth(day)
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            onTimeChanged(
                                selectedDate.withHour(hour).withMinute(minute).withSecond(0).toInstant().toEpochMilli(),
                            )
                        },
                        dateTime.hour,
                        dateTime.minute,
                        true,
                    ).show()
                },
                dateTime.year,
                dateTime.monthValue - 1,
                dateTime.dayOfMonth,
            ).show()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("日期时间：${formatter.format(dateTime)}")
    }
}

private fun Long.toYuanText(): String = BigDecimal(this).movePointLeft(2).stripTrailingZeros().toPlainString()

private fun TransactionEventEntity.defaultAccountingType(): AccountingType {
    return when (kind) {
        com.yanhuozhang.offline.core.ObservationKind.TRANSFER -> AccountingType.TRANSFER
        com.yanhuozhang.offline.core.ObservationKind.RED_PACKET -> AccountingType.TRANSFER
        com.yanhuozhang.offline.core.ObservationKind.REFUND -> AccountingType.REFUND
        com.yanhuozhang.offline.core.ObservationKind.PAYMENT -> AccountingType.EXPENSE
        com.yanhuozhang.offline.core.ObservationKind.UNKNOWN -> AccountingType.EXPENSE
    }
}

private fun TransactionEventEntity.defaultChannel(): PaymentChannel {
    return if (source == com.yanhuozhang.offline.core.EventSource.BANK_SMS) {
        PaymentChannel.BANK_CARD
    } else {
        PaymentChannel.WECHAT
    }
}

private fun statisticsEffect(
    type: AccountingType,
    direction: TransactionDirection,
    includeTransfer: Boolean,
): StatisticsEffect {
    return when (type) {
        AccountingType.INCOME -> StatisticsEffect.ADD_INCOME
        AccountingType.EXPENSE -> StatisticsEffect.ADD_EXPENSE
        AccountingType.REFUND -> StatisticsEffect.SUBTRACT_EXPENSE
        AccountingType.TRANSFER,
        AccountingType.FLOW,
        -> if (!includeTransfer) {
            StatisticsEffect.NONE
        } else if (direction == TransactionDirection.IN) {
            StatisticsEffect.ADD_INCOME
        } else {
            StatisticsEffect.ADD_EXPENSE
        }
    }
}

/**
 * 分类选择从下拉框改成横向色块网格：
 * 分类色和 emoji 在这里最有用，用户是靠图标而不是文字找分类的。
 */
@Composable
private fun CategoryPicker(
    categories: List<CategoryEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (category in categories) {
            val style = categoryStyleOf(category)
            val selected = category.id == selectedId
            Column(
                modifier = Modifier
                    .width(66.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(category.id) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .background(style.color),
                        )
                    }
                    CategoryAvatar(category = category, size = 46.dp)
                }
                Text(
                    text = category.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

fun accountingTypeLabel(type: AccountingType): String = when (type) {
    AccountingType.INCOME -> "收入"
    AccountingType.EXPENSE -> "支出"
    AccountingType.TRANSFER -> "转账"
    AccountingType.FLOW -> "资金流转"
    AccountingType.REFUND -> "退款"
}

fun paymentChannelLabel(channel: PaymentChannel): String = when (channel) {
    PaymentChannel.WECHAT -> "微信"
    PaymentChannel.BANK_CARD -> "银行卡"
    PaymentChannel.CASH -> "现金"
    PaymentChannel.OTHER -> "其他"
}

private fun recordOptionText(record: LedgerRecordEntity): String {
    return "${record.amountCents.toYuanText()} 元 · ${record.note.ifBlank { "无备注" }}"
}
