package com.yanhuozhang.offline.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanhuozhang.offline.system.PermissionHealth
import com.yanhuozhang.offline.system.PermissionHealthChecker
import com.yanhuozhang.offline.system.SystemSettingsIntents

internal enum class MainTab(val label: String) {
    HOME("首页"),
    LEDGER("账单"),
    PENDING("待确认"),
    STATISTICS("统计"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YanHuoApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableStateOf(MainTab.HOME) }
    var showSettings by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var editingRecordId by remember { mutableStateOf<Long?>(null) }
    val editingRecord = uiState.records.firstOrNull { record -> record.id == editingRecordId }
    // 大标题随滚动收缩，对应 Cashew 的 pageFramework。
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    if (!uiState.settings.onboardingCompleted) {
        OnboardingScreen(onComplete = viewModel::completeOnboarding)
        return
    }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(
            uiState = uiState,
            viewModel = viewModel,
            onBack = { showSettings = false },
        )
        return
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = if (selectedTab == MainTab.HOME) "烟火账" else selectedTab.label,
                    )
                },
                actions = {
                    Surface(
                        onClick = { showSettings = true },
                        modifier = Modifier.padding(end = 14.dp).size(42.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "设置",
                                modifier = Modifier.size(21.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                NavigationBar(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    for (tab in MainTab.entries) {
                        // 记一笔占中间那一格，和改版前的位置一致，
                        // 但换成实心圆角方块，不再是一个没有文字、和其余 tab 高度错位的 NavigationBarItem。
                        if (tab == MainTab.PENDING) {
                            // 不能用 fillMaxHeight：NavigationBar 内部的 Row 只设了 defaultMinSize，
                            // 撑满会让整个底栏拉到全屏高，把页面内容顶没。
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .align(Alignment.CenterVertically),
                                contentAlignment = Alignment.Center,
                            ) {
                                Surface(
                                    onClick = {
                                        editingRecordId = null
                                        showEditor = true
                                    },
                                    modifier = Modifier.size(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "记一笔",
                                            modifier = Modifier.size(26.dp),
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    }
                                }
                            }
                        }
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (tab == MainTab.PENDING && uiState.pendingEvents.isNotEmpty()) {
                                            Badge { Text(uiState.pendingEvents.size.toString()) }
                                        } else if (tab == MainTab.LEDGER && uiState.unclassifiedCount > 0) {
                                            Badge { Text(uiState.unclassifiedCount.toString()) }
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = when (tab) {
                                            MainTab.HOME -> Icons.Default.Home
                                            MainTab.LEDGER -> Icons.AutoMirrored.Filled.List
                                            MainTab.PENDING -> Icons.Default.PendingActions
                                            MainTab.STATISTICS -> Icons.Default.BarChart
                                        },
                                        contentDescription = null,
                                    )
                                }
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (selectedTab) {
            MainTab.HOME -> HomeScreen(
                uiState = uiState,
                modifier = Modifier.padding(padding),
                onOpenPending = { selectedTab = MainTab.PENDING },
                onOpenLedger = { selectedTab = MainTab.LEDGER },
                onEditRecord = { id -> editingRecordId = id; showEditor = true },
            )
            MainTab.LEDGER -> LedgerScreen(
                records = uiState.records,
                categories = uiState.categories,
                modifier = Modifier.padding(padding),
                onEditRecord = { id -> editingRecordId = id; showEditor = true },
                onDeleteRecord = viewModel::deleteRecord,
            )
            MainTab.PENDING -> PendingScreen(
                events = uiState.pendingEvents,
                records = uiState.records,
                categories = uiState.categories,
                modifier = Modifier.padding(padding),
                parseAmount = viewModel::parseAmount,
                onConfirm = viewModel::confirmPending,
                onMerge = viewModel::mergePending,
                onIgnore = viewModel::ignorePending,
            )
            MainTab.STATISTICS -> StatisticsScreen(
                uiState = uiState,
                modifier = Modifier.padding(padding),
                onAnalyzeCurrentMonth = viewModel::analyzeCurrentMonth,
            )
        }
    }

    if (showEditor) {
        RecordEditorDialog(
            existing = editingRecord,
            pendingEvent = null,
            categories = uiState.categories,
            records = uiState.records,
            parseAmount = viewModel::parseAmount,
            onDismiss = { showEditor = false },
            onSave = { input, rule ->
                val recordId = editingRecordId
                if (recordId == null) {
                    viewModel.addManual(input, rule)
                } else {
                    viewModel.updateRecord(recordId, input, rule)
                }
                showEditor = false
            },
        )
    }
}

@Composable
private fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var health by remember { mutableStateOf(PermissionHealthChecker.check(context)) }
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { health = PermissionHealthChecker.check(context) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { health = PermissionHealthChecker.check(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                health = PermissionHealthChecker.check(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(YanHuoDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        YanHuoPanel(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(30.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(58.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                Text("欢迎使用烟火账", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "数据只保存在本机。完成下面设置后，锁屏和后台状态下才能稳定识别交易。",
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        SectionHeader("开始前的准备", modifier = Modifier.padding(top = 6.dp))
        PermissionCard(
            title = "微信通知访问",
            description = "读取微信实际发出的支付通知，不读取颜色或截图。",
            granted = health.notificationAccessGranted,
            onClick = { SystemSettingsIntents.open(context, SystemSettingsIntents.notificationAccess()) },
        )
        PermissionCard(
            title = "微信支付页面读取",
            description = "从微信支付成功页提取金额和交易对象。",
            granted = health.accessibilityGranted,
            onClick = { SystemSettingsIntents.open(context, SystemSettingsIntents.accessibility()) },
        )
        PermissionCard(
            title = "接收新短信",
            description = "仅处理新收到的微信支付结果短信和疑似财付通银行短信，不读取历史短信。",
            granted = health.smsPermissionGranted,
            onClick = {
                if (runCatching { smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS) }.isFailure) {
                    SystemSettingsIntents.open(context, SystemSettingsIntents.appDetails(context))
                }
            },
        )
        PermissionCard(
            title = "待确认提醒",
            description = "只在转账、退款、歧义或权限异常时通知。",
            granted = health.appNotificationsGranted,
            onClick = {
                val requested = Build.VERSION.SDK_INT >= 33 &&
                    runCatching {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }.isSuccess
                if (!requested) {
                    SystemSettingsIntents.open(context, SystemSettingsIntents.appNotifications(context))
                }
            },
        )
        PermissionCard(
            title = "HyperOS 自启动",
            description = "请在小米安全中心允许烟火账自启动。此项无法由应用自动判断。",
            granted = null,
            onClick = { SystemSettingsIntents.open(context, SystemSettingsIntents.xiaomiAutoStart(context)) },
        )
        PermissionCard(
            title = "电池无限制",
            description = "建议把烟火账的电池策略设置为无限制。",
            granted = health.batteryOptimizationDisabled,
            onClick = { SystemSettingsIntents.open(context, SystemSettingsIntents.batteryOptimization()) },
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("进入烟火账")
        }
        Text(
            text = "未完成的权限可稍后在设置页继续处理。",
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean?,
    onClick: () -> Unit,
) {
    YanHuoActionPanel(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            StatusPill(
                text = when (granted) {
                    true -> "已完成"
                    false -> "去设置"
                    null -> "检查"
                },
                positive = granted == true,
            )
        }
    }
}
