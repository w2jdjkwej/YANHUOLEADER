package com.yanhuozhang.offline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanhuozhang.offline.core.EventSource
import com.yanhuozhang.offline.core.PaymentChannel
import com.yanhuozhang.offline.core.StatisticsCalculator
import com.yanhuozhang.offline.core.StatisticsEntry
import com.yanhuozhang.offline.core.TransactionDirection
import com.yanhuozhang.offline.data.CategoryEntity
import com.yanhuozhang.offline.data.LedgerInput
import com.yanhuozhang.offline.data.LedgerRecordEntity
import com.yanhuozhang.offline.data.MonthlyAnalysisReportEntity
import com.yanhuozhang.offline.data.PersonalRuleInput
import com.yanhuozhang.offline.data.TransactionEventEntity
import com.yanhuozhang.offline.ui.theme.moneyColors
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

// Scaffold 的 innerPadding 已经把底栏高度让出来了，这里只补一点呼吸空间。
// 之前给的是 104dp，和 Scaffold 的让位叠加，滚到底会多出一大截空白。
private val ListBottomSpace = 16.dp

// ===========================================================================
// 首页
// ===========================================================================

@Composable
fun HomeScreen(
    uiState: MainUiState,
    modifier: Modifier = Modifier,
    onOpenPending: () -> Unit,
    onOpenLedger: () -> Unit,
    onEditRecord: (Long) -> Unit,
) {
    val appearState = rememberAppearAnimationState()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = YanHuoDimens.ScreenPadding,
            end = YanHuoDimens.ScreenPadding,
            top = 4.dp,
            bottom = ListBottomSpace,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "hero") {
            FadeSlideIn(key = "hero", state = appearState) {
                BalanceHeroCard(uiState.dashboard)
            }
        }
        item(key = "pending") {
            FadeSlideIn(key = "pending", state = appearState, delayMillis = 40) {
                PendingOverview(
                    pendingCount = uiState.pendingEvents.size,
                    onClick = onOpenPending,
                )
            }
        }
        item(key = "recent-header") {
            SectionHeader(
                title = "最近账单",
                modifier = Modifier.padding(top = 6.dp),
                action = {
                    if (uiState.records.isNotEmpty()) {
                        TextButton(onClick = onOpenLedger) { Text("查看全部") }
                    }
                },
            )
        }
        if (uiState.records.isEmpty()) {
            item(key = "recent-empty") {
                EmptyHint("还没有账单", "点底部中间的加号记第一笔。")
            }
        } else {
            item(key = "recent-list") {
                FadeSlideIn(key = "recent-list", state = appearState, delayMillis = 80) {
                    RecordGroupPanel(
                        records = uiState.records.take(8),
                        categories = uiState.categories,
                        onEditRecord = onEditRecord,
                    )
                }
            }
        }
    }
}

/**
 * Cashew 的招牌结构：上半是强调色调出的信息头，下半是浅色数据条，两段之间没有描边。
 */
@Composable
private fun BalanceHeroCard(summary: DashboardSummary) {
    YanHuoPanel(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(30.dp),
    ) {
        Column {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "本月结余",
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelLarge,
                )
                AnimatedAmountText(
                    cents = summary.monthBalanceCents,
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroStat("收入", summary.monthIncomeCents, moneyColors.income, Modifier.weight(1f))
                HeroStatDivider()
                HeroStat("支出", summary.monthExpenseCents, moneyColors.expense, Modifier.weight(1f))
                HeroStatDivider()
                HeroStat("今日支出", summary.todayExpenseCents, moneyColors.expense, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, cents: Long, accent: Color, modifier: Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        AnimatedAmountText(
            cents = cents,
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            symbolScale = 0.7f,
        )
    }
}

@Composable
private fun HeroStatDivider() {
    Box(
        modifier = Modifier
            .height(26.dp)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}

@Composable
private fun PendingOverview(pendingCount: Int, onClick: () -> Unit) {
    val hasPending = pendingCount > 0
    YanHuoActionPanel(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (hasPending) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(46.dp),
                shape = RoundedCornerShape(16.dp),
                color = if (hasPending) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.HourglassEmpty,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (hasPending) {
                            MaterialTheme.colorScheme.onTertiary
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("待确认交易", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (hasPending) "有 $pendingCount 笔交易需要核对" else "所有自动识别交易都已处理",
                    color = if (hasPending) {
                        MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (hasPending) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            } else {
                StatusPill(text = "已清空", positive = true)
            }
        }
    }
}

// ===========================================================================
// 账单
// ===========================================================================

@Composable
fun LedgerScreen(
    records: List<LedgerRecordEntity>,
    categories: List<CategoryEntity>,
    modifier: Modifier = Modifier,
    onEditRecord: (Long) -> Unit,
    onDeleteRecord: (Long) -> Unit,
) {
    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var paymentFilter by remember { mutableStateOf<PaymentChannel?>(null) }
    var deletingRecord by remember { mutableStateOf<LedgerRecordEntity?>(null) }
    val appearState = rememberAppearAnimationState()
    val zone = ZoneId.systemDefault()

    val monthRecords = records.filter { record ->
        YearMonth.from(Instant.ofEpochMilli(record.transactionTimeMillis).atZone(zone)) == selectedMonth
    }
    val filtered = monthRecords.filter { record ->
        val textMatches = searchText.isBlank() ||
            record.note.contains(searchText, ignoreCase = true) ||
            record.counterparty.orEmpty().contains(searchText, ignoreCase = true)
        textMatches && (paymentFilter == null || record.paymentChannel == paymentFilter)
    }
    val grouped = filtered.groupBy { record ->
        Instant.ofEpochMilli(record.transactionTimeMillis).atZone(zone).toLocalDate()
    }.toSortedMap(compareByDescending { date -> date })

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = YanHuoDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MonthSelector(
            selectedMonth = selectedMonth,
            monthTotals = StatisticsCalculator.calculate(monthRecords.toStatisticsEntries()).let { totals ->
                totals.incomeCents to totals.expenseCents
            },
            searchOpen = searchOpen,
            onPrevious = { selectedMonth = selectedMonth.minusMonths(1) },
            onNext = { selectedMonth = selectedMonth.plusMonths(1) },
            onToggleSearch = {
                searchOpen = !searchOpen
                if (!searchOpen) {
                    searchText = ""
                    paymentFilter = null
                }
            },
        )
        if (searchOpen) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { value -> searchText = value },
                placeholder = { Text("搜索备注或交易对象") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(YanHuoDimens.ItemRadius),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PaymentFilterChip("全部", paymentFilter == null) { paymentFilter = null }
                for (channel in listOf(PaymentChannel.WECHAT, PaymentChannel.BANK_CARD, PaymentChannel.CASH)) {
                    PaymentFilterChip(paymentChannelLabel(channel), paymentFilter == channel) {
                        paymentFilter = channel
                    }
                }
            }
        }

        if (grouped.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyHint("没有找到账单", "换一个月份或筛选条件试试。")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = ListBottomSpace),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                for ((date, dayRecords) in grouped) {
                    item(key = "day-$date") {
                        FadeSlideIn(key = "day-$date", state = appearState) {
                            DayGroup(
                                date = date,
                                records = dayRecords,
                                categories = categories,
                                onEditRecord = onEditRecord,
                                onDeleteRecord = { record -> deletingRecord = record },
                            )
                        }
                    }
                }
            }
        }
    }

    deletingRecord?.let { record ->
        YanHuoDialog(
            onDismissRequest = { deletingRecord = null },
            title = "删除这笔账单？",
            content = {
                Text(
                    text = "账单会进入已删除列表，相关观察指纹将保留以防重复导入。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            actions = {
                TextButton(onClick = { deletingRecord = null }) { Text("取消") }
                Button(
                    onClick = { onDeleteRecord(record.id); deletingRecord = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("删除") }
            },
        )
    }
}

@Composable
private fun PaymentFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = CircleShape,
    )
}

/** 一天一组：日期与当日净额在组标题上，组内是无描边的行。 */
@Composable
private fun DayGroup(
    date: LocalDate,
    records: List<LedgerRecordEntity>,
    categories: List<CategoryEntity>,
    onEditRecord: (Long) -> Unit,
    onDeleteRecord: (LedgerRecordEntity) -> Unit,
) {
    val totals = StatisticsCalculator.calculate(records.toStatisticsEntries())
    val net = totals.incomeCents - totals.expenseCents
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dayLabel(date),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            AmountText(
                cents = net,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    net > 0 -> moneyColors.income
                    net < 0 -> moneyColors.expense
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                showPlusSign = true,
                symbolScale = 0.78f,
            )
        }
        RecordGroupPanel(
            records = records,
            categories = categories,
            onEditRecord = onEditRecord,
            onDeleteRecord = onDeleteRecord,
        )
    }
}

@Composable
private fun MonthSelector(
    selectedMonth: YearMonth,
    monthTotals: Pair<Long, Long>,
    searchOpen: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleSearch: () -> Unit,
) {
    val (income, expense) = monthTotals
    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.Default.ChevronLeft, "上个月", onPrevious)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${selectedMonth.year} 年 ${selectedMonth.monthValue} 月",
                    style = MaterialTheme.typography.titleLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AmountText(
                        cents = income,
                        style = MaterialTheme.typography.bodySmall,
                        color = moneyColors.income,
                        showPlusSign = true,
                        symbolScale = 0.85f,
                    )
                    AmountText(
                        cents = -expense,
                        style = MaterialTheme.typography.bodySmall,
                        color = moneyColors.expense,
                        symbolScale = 0.85f,
                    )
                }
            }
            RoundIconButton(Icons.Default.ChevronRight, "下个月", onNext)
            RoundIconButton(
                imageVector = if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                contentDescription = if (searchOpen) "关闭搜索" else "搜索",
                onClick = onToggleSearch,
                highlighted = searchOpen,
            )
        }
    }
}

@Composable
private fun RoundIconButton(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

// ===========================================================================
// 待确认
// ===========================================================================

@Composable
fun PendingScreen(
    events: List<TransactionEventEntity>,
    records: List<LedgerRecordEntity>,
    categories: List<CategoryEntity>,
    modifier: Modifier = Modifier,
    parseAmount: (String) -> Long?,
    onConfirm: (Long, LedgerInput, PersonalRuleInput?) -> Unit,
    onMerge: (Long, Long) -> Unit,
    onIgnore: (Long) -> Unit,
) {
    var confirmEvent by remember { mutableStateOf<TransactionEventEntity?>(null) }
    var mergeEvent by remember { mutableStateOf<TransactionEventEntity?>(null) }
    var ignoreEvent by remember { mutableStateOf<TransactionEventEntity?>(null) }
    val appearState = rememberAppearAnimationState()

    if (events.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize().padding(YanHuoDimens.ScreenPadding),
            contentAlignment = Alignment.Center,
        ) {
            EmptyHint("没有待确认交易", "自动识别到需要核对的交易后，会显示在这里。")
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = YanHuoDimens.ScreenPadding,
                end = YanHuoDimens.ScreenPadding,
                top = 4.dp,
                bottom = ListBottomSpace,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "pending-hint") {
                YanHuoPanel(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = "这些事件在确认前不会计入收支统计。请核对金额、类型和分类。",
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(events, key = { event -> event.id }) { event ->
                FadeSlideIn(key = "event-${event.id}", state = appearState) {
                    PendingEventCard(
                        event = event,
                        onConfirm = { confirmEvent = event },
                        onMerge = { mergeEvent = event },
                        onIgnore = { ignoreEvent = event },
                    )
                }
            }
        }
    }

    confirmEvent?.let { event ->
        RecordEditorDialog(
            existing = null,
            pendingEvent = event,
            categories = categories,
            records = records,
            parseAmount = parseAmount,
            onDismiss = { confirmEvent = null },
            onSave = { input, rule -> onConfirm(event.id, input, rule); confirmEvent = null },
        )
    }
    mergeEvent?.let { event ->
        YanHuoDialog(
            onDismissRequest = { mergeEvent = null },
            title = "合并到已有账单",
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "选择一笔已有账单，当前识别事件将关联到该账单。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        items(records.take(50), key = { record -> record.id }) { record ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onMerge(event.id, record.id)
                                        mergeEvent = null
                                    }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = record.note.ifBlank { "无备注" },
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                AmountText(
                                    cents = record.amountCents,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                        }
                    }
                }
            },
            actions = {
                TextButton(onClick = { mergeEvent = null }) { Text("取消") }
            },
        )
    }
    ignoreEvent?.let { event ->
        YanHuoDialog(
            onDismissRequest = { ignoreEvent = null },
            title = "忽略这个事件？",
            content = {
                Text(
                    text = "同一来源事件再次出现时不会重新入账。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            actions = {
                TextButton(onClick = { ignoreEvent = null }) { Text("取消") }
                Button(onClick = { onIgnore(event.id); ignoreEvent = null }) { Text("忽略") }
            },
        )
    }
}

@Composable
private fun PendingEventCard(
    event: TransactionEventEntity,
    onConfirm: () -> Unit,
    onMerge: () -> Unit,
    onIgnore: () -> Unit,
) {
    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                StatusPill(sourceLabel(event.source), positive = true)
                if (event.amountCents != null) {
                    AmountText(
                        cents = event.amountCents,
                        style = MaterialTheme.typography.headlineSmall,
                        symbolScale = 0.62f,
                    )
                } else {
                    Text("金额未知", style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(
                text = event.rawText.take(120),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("确认")
                }
                FilledTonalButton(
                    onClick = onMerge,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CallMerge,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("合并")
                }
                FilledTonalButton(
                    onClick = onIgnore,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("忽略")
                }
            }
        }
    }
}

// ===========================================================================
// 统计
// ===========================================================================

@Composable
fun StatisticsScreen(
    uiState: MainUiState,
    modifier: Modifier = Modifier,
    onAnalyzeCurrentMonth: () -> Unit,
    onOpenMonthlyAnalysis: () -> Unit,
) {
    val appearState = rememberAppearAnimationState()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = YanHuoDimens.ScreenPadding,
            end = YanHuoDimens.ScreenPadding,
            top = 4.dp,
            bottom = ListBottomSpace,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "stats-hero") {
            FadeSlideIn(key = "stats-hero", state = appearState) {
                BalanceHeroCard(uiState.dashboard)
            }
        }
        item(key = "trend-header") {
            SectionHeader("最近 6 个月收支", modifier = Modifier.padding(top = 6.dp))
        }
        item(key = "trend") {
            FadeSlideIn(key = "trend", state = appearState, delayMillis = 40) {
                YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
                    TrendChart(
                        points = uiState.trend,
                        modifier = Modifier.padding(18.dp),
                    )
                }
            }
        }
        item(key = "category-header") {
            SectionHeader("本月分类支出", modifier = Modifier.padding(top = 6.dp))
        }
        if (uiState.categoryRanking.isEmpty()) {
            item(key = "category-empty") {
                EmptyHint("本月还没有支出", "产生支出账单后会显示分类占比。")
            }
        } else {
            item(key = "category-breakdown") {
                FadeSlideIn(key = "category-breakdown", state = appearState, delayMillis = 80) {
                    CategoryBreakdownPanel(
                        items = uiState.categoryRanking,
                        categories = uiState.categories,
                    )
                }
            }
        }
        item(key = "analysis-header") {
            SectionHeader("DeepSeek 月度分析", modifier = Modifier.padding(top = 6.dp))
        }
        item(key = "analysis") {
            MonthlyAnalysisCard(
                report = uiState.latestMonthlyAnalysis,
                deepSeekEnabled = uiState.settings.deepSeekEnabled,
                hasCurrentMonthExpense = uiState.categoryRanking.isNotEmpty(),
                running = uiState.monthlyAnalysisRunning,
                onAnalyzeCurrentMonth = onAnalyzeCurrentMonth,
                onOpenMonthlyAnalysis = onOpenMonthlyAnalysis,
            )
        }
    }
}

/**
 * 环形图与下面的分类行共用同一套分类色，
 * 改版前饼图用的是另一套调色板、图例还写着“第 1 类”，和列表完全对不上。
 */
@Composable
private fun CategoryBreakdownPanel(
    items: List<CategoryExpense>,
    categories: List<CategoryEntity>,
) {
    val slices = items
        .filter { item -> item.expenseCents > 0 }
        .map { item ->
            val category = categories.firstOrNull { candidate -> candidate.id == item.categoryId }
            CategorySlice(
                categoryId = item.categoryId,
                name = category?.name ?: "未分类",
                expenseCents = item.expenseCents,
                style = categoryStyleOf(category),
                category = category,
            )
        }
    if (slices.isEmpty()) {
        EmptyHint("本月还没有支出", "产生支出账单后会显示分类占比。")
        return
    }
    val total = slices.sumOf { slice -> slice.expenseCents }

    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            CategoryDonut(slices = slices, total = total)
            Column(modifier = Modifier.fillMaxWidth()) {
                for ((index, slice) in slices.withIndex()) {
                    CategoryBreakdownRow(
                        slice = slice,
                        fraction = slice.expenseCents.toFloat() / total.toFloat(),
                    )
                    if (index != slices.lastIndex) {
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

private data class CategorySlice(
    val categoryId: Long?,
    val name: String,
    val expenseCents: Long,
    val style: CategoryStyle,
    val category: CategoryEntity?,
)

@Composable
private fun CategoryDonut(slices: List<CategorySlice>, total: Long) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val gapDegrees = if (slices.size > 1) 2.5f else 0f

    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(186.dp)) {
            val strokeWidth = 26.dp.toPx()
            drawCircle(color = trackColor, style = Stroke(width = strokeWidth))
            var startAngle = -90f
            for (slice in slices) {
                val sweep = slice.expenseCents.toFloat() / total.toFloat() * 360f
                if (sweep > gapDegrees) {
                    drawArc(
                        color = slice.style.color,
                        startAngle = startAngle + gapDegrees / 2f,
                        sweepAngle = sweep - gapDegrees,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
                startAngle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "本月支出",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            AnimatedAmountText(
                cents = total,
                style = MaterialTheme.typography.headlineSmall,
                symbolScale = 0.64f,
            )
        }
    }
}

@Composable
private fun CategoryBreakdownRow(slice: CategorySlice, fraction: Float) {
    val percent = (fraction * 100).toInt().coerceAtLeast(1)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryAvatar(category = slice.category, size = 40.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = slice.name,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                AmountText(
                    cents = slice.expenseCents,
                    style = MaterialTheme.typography.titleSmall,
                    symbolScale = 0.78f,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryProgressBar(
                    fraction = fraction,
                    color = slice.style.color,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "$percent%",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun MonthlyAnalysisCard(
    report: MonthlyAnalysisReportEntity?,
    deepSeekEnabled: Boolean,
    hasCurrentMonthExpense: Boolean,
    running: Boolean,
    onAnalyzeCurrentMonth: () -> Unit,
    onOpenMonthlyAnalysis: () -> Unit,
) {
    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (report == null) {
                Text("尚未生成月度分析", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "应用会在每月最后一天 23:30 完成本地统计，并使用设置中的 DeepSeek 配置生成结构化消费月报。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val month = runCatching { YearMonth.parse(report.monthKey) }.getOrNull()
                val monthLabel = month?.let { value ->
                    "${value.year} 年 ${value.monthValue} 月"
                } ?: report.monthKey
                Text("$monthLabel 消费分析", style = MaterialTheme.typography.titleMedium)
                Text(report.summary, style = MaterialTheme.typography.bodyLarge)
                val observations = listOfNotNull(
                    report.observation1,
                    report.observation2,
                    report.observation3,
                )
                for (observation in observations) {
                    Text(
                        text = "• $observation",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                report.suggestion?.let { suggestion ->
                    Text("建议", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = suggestion,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                report.lightComment?.let { lightComment ->
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = RoundedCornerShape(YanHuoDimens.ItemRadius),
                    ) {
                        Text(
                            text = lightComment,
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                val thinkingLabel = if (report.thinkingMode == "enabled") "思考" else "非思考"
                Text(
                    text = "${report.model} · $thinkingLabel",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = onAnalyzeCurrentMonth,
                enabled = deepSeekEnabled && hasCurrentMonthExpense && !running,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(if (running) "正在分析…" else "立即分析本月")
            }
            FilledTonalButton(
                onClick = onOpenMonthlyAnalysis,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("查看完整月报与历史")
            }
            if (!deepSeekEnabled) {
                Text(
                    text = "请先在设置中启用 DeepSeek。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TrendChart(points: List<MonthlyTrendPoint>, modifier: Modifier = Modifier) {
    if (points.isEmpty()) {
        EmptyHint("暂无趋势数据", "记录账单后会显示最近 6 个月的变化。")
        return
    }
    val incomeColor = moneyColors.income
    val expenseColor = moneyColors.expense
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val maxAmount = max(1L, points.maxOf { point -> max(point.incomeCents, point.expenseCents) })

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ChartLegend("收入", incomeColor)
            ChartLegend("支出", expenseColor)
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(170.dp)) {
            val barWidth = 11.dp.toPx()
            val bottomPadding = 6.dp.toPx()
            val groupWidth = size.width / points.size
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()))
            for (gridIndex in 0..3) {
                val y = size.height * gridIndex / 3f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = if (gridIndex == 3) null else dashEffect,
                )
            }
            points.forEachIndexed { index, point ->
                val centerX = groupWidth * index + groupWidth / 2f
                val incomeGeometry = trendBarGeometry(
                    canvasHeight = size.height,
                    barWidth = barWidth,
                    bottomPadding = bottomPadding,
                    amount = point.incomeCents,
                    maximumAmount = maxAmount,
                )
                val expenseGeometry = trendBarGeometry(
                    canvasHeight = size.height,
                    barWidth = barWidth,
                    bottomPadding = bottomPadding,
                    amount = point.expenseCents,
                    maximumAmount = maxAmount,
                )
                if (incomeGeometry.shouldDraw) {
                    val x = centerX - barWidth * 0.72f
                    drawLine(
                        color = incomeColor,
                        start = Offset(x, incomeGeometry.baselineY),
                        end = Offset(x, incomeGeometry.topY),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Round,
                    )
                }
                if (expenseGeometry.shouldDraw) {
                    val x = centerX + barWidth * 0.72f
                    drawLine(
                        color = expenseColor,
                        start = Offset(x, expenseGeometry.baselineY),
                        end = Offset(x, expenseGeometry.topY),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            points.forEach { point ->
                Text(
                    text = point.label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ChartLegend(label: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

// ===========================================================================
// 共用行
// ===========================================================================

@Composable
private fun RecordGroupPanel(
    records: List<LedgerRecordEntity>,
    categories: List<CategoryEntity>,
    onEditRecord: (Long) -> Unit,
    onDeleteRecord: ((LedgerRecordEntity) -> Unit)? = null,
) {
    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
        Column {
            records.forEachIndexed { index, record ->
                RecordRow(
                    record = record,
                    categories = categories,
                    onClick = { onEditRecord(record.id) },
                    onDelete = onDeleteRecord?.let { delete -> { delete(record) } },
                )
                if (index != records.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 74.dp, end = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordRow(
    record: LedgerRecordEntity,
    categories: List<CategoryEntity>,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val category = categories.firstOrNull { item -> item.id == record.categoryId }
    val categoryName = category?.name ?: "未分类"
    val isIncome = record.direction == TransactionDirection.IN
    val detail = listOf(
        paymentChannelLabel(record.paymentChannel),
        record.counterparty,
        record.note,
    ).filterNot { value -> value.isNullOrBlank() }.joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryAvatar(category = category)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(categoryName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = detail.ifBlank { accountingTypeLabel(record.accountingType) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        AmountText(
            cents = if (isIncome) record.amountCents else -record.amountCents,
            style = MaterialTheme.typography.titleMedium,
            color = if (isIncome) moneyColors.income else moneyColors.expense,
            showPlusSign = true,
            symbolScale = 0.72f,
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "删除",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(title: String, description: String? = null) {
    YanHuoPanel(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(54.dp),
                shape = RoundedCornerShape(19.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Inbox,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (description != null) {
                Text(
                    text = description,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ===========================================================================
// 文本工具
// ===========================================================================

private val dayOfMonthFormatter = DateTimeFormatter.ofPattern("M 月 d 日")

private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> date.format(dayOfMonthFormatter)
    }
}

private fun List<LedgerRecordEntity>.toStatisticsEntries(): List<StatisticsEntry> =
    map { record -> StatisticsEntry(record.amountCents, record.statisticsEffect) }

private fun sourceLabel(source: EventSource): String = when (source) {
    EventSource.WECHAT_NOTIFICATION -> "微信通知"
    EventSource.WECHAT_SMS -> "微信支付结果短信"
    EventSource.WECHAT_PAYMENT_PAGE -> "微信支付页面"
    EventSource.BANK_SMS -> "银行短信"
    EventSource.MANUAL -> "手动"
}
