package com.yanhuozhang.offline.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanhuozhang.offline.settings.DeepSeekModel
import com.yanhuozhang.offline.settings.DeepSeekThinkingMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yanhuozhang.offline.core.AccountingType
import com.yanhuozhang.offline.core.DefaultCategories
import com.yanhuozhang.offline.data.CategoryEntity
import com.yanhuozhang.offline.system.PermissionHealthChecker
import com.yanhuozhang.offline.system.SystemSettingsIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private enum class PasswordAction {
    EXPORT,
    IMPORT,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(uiState: MainUiState, viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var permissionHealth by remember { mutableStateOf(PermissionHealthChecker.check(context)) }
    var reminderHourText by remember(uiState.settings.reminderHour) { mutableStateOf(uiState.settings.reminderHour.toString()) }
    var reminderMinuteText by remember(uiState.settings.reminderMinute) { mutableStateOf(uiState.settings.reminderMinute.toString()) }
    var deepSeekApiKeyText by remember(uiState.settings.deepSeekApiKey) {
        mutableStateOf(uiState.settings.deepSeekApiKey)
    }
    var addingCategory by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var passwordAction by remember { mutableStateOf<PasswordAction?>(null) }
    var importBytes by remember { mutableStateOf<ByteArray?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }

    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionHealth = PermissionHealthChecker.check(context)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionHealth = PermissionHealthChecker.check(context)
    }
    var pendingExportPassword by remember { mutableStateOf<CharArray?>(null) }
    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val password = pendingExportPassword
        pendingExportPassword = null
        if (uri == null || password == null) {
            password?.fill('\u0000')
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { viewModel.createEncryptedBackup(password) }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream -> stream.write(bytes) }
                        ?: error("无法写入备份文件")
                }
                statusText = "备份已导出"
            } catch (error: Exception) {
                statusText = error.message ?: "备份失败"
            } finally {
                password.fill('\u0000')
            }
        }
    }
    val openBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    importBytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
                            ?: error("无法读取备份文件")
                    }
                    passwordAction = PasswordAction.IMPORT
                } catch (error: Exception) {
                    statusText = error.message ?: "读取失败"
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionHealth = PermissionHealthChecker.check(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 设置页是在 YanHuoApp 提前 return 的分支里渲染的，那边收 messages 的
    // LaunchedEffect 已经销毁。没有这个 host，像“请先填写 DeepSeek API Key”
    // 这类失败提示会被直接吞掉，用户只会看到开关点了没反应。
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    val notify: (String) -> Unit = { message ->
        statusText = message
        scope.launch { snackbarHostState.showSnackbar(message) }
    }
    val openSettings: (Intent) -> Unit = { intent ->
        if (!SystemSettingsIntents.open(context, intent)) {
            notify("无法打开系统设置页，请手动前往系统设置完成授权")
        }
    }
    // 权限申请一旦发不出去就退到应用详情页，让用户还能手动授权，
    // 不要静默失败——那样按钮看起来就是点了没反应。
    val requestPermission: (() -> Unit) -> Unit = { launch ->
        if (runCatching { launch() }.isFailure) {
            notify("无法弹出权限对话框，已跳转到应用权限页")
            openSettings(SystemSettingsIntents.appDetails(context))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("设置", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "管理权限、分类与本地数据",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = YanHuoDimens.ScreenPadding,
                end = YanHuoDimens.ScreenPadding,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle("权限与后台运行") }
            item {
                SettingAction("微信通知访问", permissionHealth.notificationAccessGranted) {
                    openSettings(SystemSettingsIntents.notificationAccess())
                }
            }
            item {
                SettingAction("微信支付页面读取", permissionHealth.accessibilityGranted) {
                    openSettings(SystemSettingsIntents.accessibility())
                }
            }
            item {
                SettingAction("接收新短信", permissionHealth.smsPermissionGranted) {
                    requestPermission { smsLauncher.launch(Manifest.permission.RECEIVE_SMS) }
                }
            }
            item {
                SettingAction("待确认通知", permissionHealth.appNotificationsGranted) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestPermission { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    } else {
                        openSettings(SystemSettingsIntents.appNotifications(context))
                    }
                }
            }
            item {
                SettingAction("HyperOS 自启动", null) {
                    openSettings(SystemSettingsIntents.xiaomiAutoStart(context))
                }
            }
            item {
                SettingAction("电池无限制", permissionHealth.batteryOptimizationDisabled) {
                    openSettings(SystemSettingsIntents.batteryOptimization())
                }
            }

            item { SectionTitle("提醒") }
            item {
                YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("每日待确认汇总", modifier = Modifier.weight(1f))
                            Switch(
                                checked = uiState.settings.reminderEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.updateReminder(
                                        enabled,
                                        reminderHourText.toIntOrNull() ?: 20,
                                        reminderMinuteText.toIntOrNull() ?: 0,
                                    )
                                },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = reminderHourText,
                                onValueChange = { reminderHourText = it },
                                label = { Text("时") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = reminderMinuteText,
                                onValueChange = { reminderMinuteText = it },
                                label = { Text("分") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            Button(onClick = {
                                val hour = reminderHourText.toIntOrNull()
                                val minute = reminderMinuteText.toIntOrNull()
                                if (hour != null && hour in 0..23 && minute != null && minute in 0..59) {
                                    viewModel.updateReminder(uiState.settings.reminderEnabled, requireNotNull(hour), requireNotNull(minute))
                                } else {
                                    statusText = "请输入有效时间"
                                }
                            }) { Text("保存") }
                        }
                    }
                }
            }

            item { SectionTitle("应用锁") }
            item {
                YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("生物识别或设备凭据", modifier = Modifier.weight(1f))
                            Switch(
                                checked = uiState.settings.appLockEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.updateAppLock(enabled, uiState.settings.lockTimeoutSeconds)
                                },
                            )
                        }
                        Text("离开应用后锁定", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (seconds in listOf(0, 60, 300)) {
                                FilterChip(
                                    selected = uiState.settings.lockTimeoutSeconds == seconds,
                                    onClick = { viewModel.updateAppLock(true, seconds) },
                                    label = { Text(if (seconds == 0) "立即" else "${seconds / 60} 分钟") },
                                )
                            }
                        }
                    }
                }
            }

            item { SectionTitle("自动分类") }
            item {
                YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("DeepSeek 辅助分类")
                                Text(
                                    text = if (deepSeekApiKeyText.isBlank()) {
                                        "需要先填写下方的 API Key 才能启用"
                                    } else {
                                        "用于本地规则无法分类的账单，也用于月底支出分析"
                                    },
                                    color = if (deepSeekApiKeyText.isBlank()) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = uiState.settings.deepSeekEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.updateDeepSeek(
                                        enabled = enabled,
                                        apiKey = deepSeekApiKeyText,
                                        model = uiState.settings.deepSeekModel,
                                        thinkingMode = uiState.settings.deepSeekThinkingMode,
                                    )
                                },
                            )
                        }
                        OutlinedTextField(
                            value = deepSeekApiKeyText,
                            onValueChange = { deepSeekApiKeyText = it },
                            label = { Text("DeepSeek API Key") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("模型", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (model in DeepSeekModel.entries) {
                                FilterChip(
                                    selected = uiState.settings.deepSeekModel == model,
                                    onClick = {
                                        viewModel.updateDeepSeek(
                                            enabled = uiState.settings.deepSeekEnabled,
                                            apiKey = deepSeekApiKeyText,
                                            model = model,
                                            thinkingMode = uiState.settings.deepSeekThinkingMode,
                                        )
                                    },
                                    label = { Text(model.displayName) },
                                )
                            }
                        }
                        Text("响应模式", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (thinkingMode in DeepSeekThinkingMode.entries) {
                                FilterChip(
                                    selected =
                                        uiState.settings.deepSeekThinkingMode == thinkingMode,
                                    onClick = {
                                        viewModel.updateDeepSeek(
                                            enabled = uiState.settings.deepSeekEnabled,
                                            apiKey = deepSeekApiKeyText,
                                            model = uiState.settings.deepSeekModel,
                                            thinkingMode = thinkingMode,
                                        )
                                    },
                                    label = { Text(thinkingMode.displayName) },
                                )
                            }
                        }
                        Button(
                            onClick = {
                                viewModel.updateDeepSeek(
                                    enabled = uiState.settings.deepSeekEnabled,
                                    apiKey = deepSeekApiKeyText,
                                    model = uiState.settings.deepSeekModel,
                                    thinkingMode = uiState.settings.deepSeekThinkingMode,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("保存 DeepSeek 设置")
                        }
                        Text(
                            text = "思考模式可能更慢并消耗更多输出 Token。无法可靠判断、网络异常或额度不足时，账单会保持未分类并提醒手动处理。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item { SectionTitle("分类管理") }
            item {
                Button(
                    onClick = { addingCategory = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) {
                    Text("新增分类")
                }
            }
            items(uiState.categories, key = { category -> category.id }) { category ->
                YanHuoActionPanel(
                    onClick = { editingCategory = category },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryAvatar(category = category, size = 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(category.name)
                            Text(
                                accountingTypeLabel(category.accountingType),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        StatusPill(
                            text = if (category.isActive) "使用中" else "已停用",
                            positive = category.isActive,
                        )
                    }
                }
            }

            item { SectionTitle("个人规则") }
            if (uiState.rules.isEmpty()) {
                item {
                    YanHuoPanel(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Text(
                            text = "尚未保存个人规则。手动选择分类后可以记住该交易对象。",
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                items(uiState.rules, key = { rule -> rule.id }) { rule ->
                    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text("“${rule.matchText}”", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "自动归为${accountingTypeLabel(rule.accountingType)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            item { SectionTitle("备份与恢复") }
            item {
                YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { passwordAction = PasswordAction.EXPORT },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("导出备份")
                            }
                            Button(
                                onClick = {
                                    runCatching {
                                        openBackupLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                                    }.onFailure { notify("无法打开文件选择器") }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("恢复备份")
                            }
                        }
                        Text(
                            text = "密码无法找回。恢复会在完整校验后事务性替换现有数据。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item { SectionTitle("已删除账单") }
            if (uiState.deletedRecords.isEmpty()) {
                item {
                    YanHuoPanel(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Text(
                            text = "没有已删除账单。",
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(uiState.deletedRecords, key = { record -> record.id }) { record ->
                    YanHuoPanel(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AmountText(
                                cents = record.amountCents,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = record.note.ifBlank { "无备注" },
                                modifier = Modifier.weight(1f).padding(start = 10.dp),
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { viewModel.restoreRecord(record.id) }) { Text("恢复") }
                        }
                    }
                }
            }
            statusText?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (addingCategory) {
        CategoryDialog(
            title = "新增分类",
            initialName = "",
            initialType = AccountingType.EXPENSE,
            showType = true,
            initialOrder = uiState.categories.size,
            initialActive = true,
            initialColorHex = null,
            initialEmoji = null,
            onDismiss = { addingCategory = false },
            onSave = { name, type, order, _, colorHex, emoji ->
                viewModel.addCategory(name, type, order, colorHex, emoji)
                addingCategory = false
            },
        )
    }
    editingCategory?.let { category ->
        CategoryDialog(
            title = "编辑分类",
            initialName = category.name,
            initialType = category.accountingType,
            showType = false,
            initialOrder = category.sortOrder,
            initialActive = category.isActive,
            initialColorHex = category.colorHex,
            initialEmoji = category.emoji,
            onDismiss = { editingCategory = null },
            onSave = { name, _, order, active, colorHex, emoji ->
                viewModel.updateCategory(category, name, order, active, colorHex, emoji)
                editingCategory = null
            },
        )
    }
    passwordAction?.let { action ->
        PasswordDialog(
            title = if (action == PasswordAction.EXPORT) "设置备份密码" else "输入备份密码",
            onDismiss = { passwordAction = null; importBytes = null },
            onConfirm = { password ->
                passwordAction = null
                if (action == PasswordAction.EXPORT) {
                    pendingExportPassword = password
                    val fileName = "烟火账-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.yhzbak"
                    runCatching { createBackupLauncher.launch(fileName) }.onFailure {
                        pendingExportPassword = null
                        password.fill('\u0000')
                        notify("无法打开文件保存对话框")
                    }
                } else {
                    val bytes = importBytes
                    importBytes = null
                    if (bytes != null) {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { viewModel.restoreEncryptedBackup(bytes, password) }
                                statusText = "备份已恢复"
                            } catch (error: Exception) {
                                statusText = error.message ?: "恢复失败"
                            } finally {
                                password.fill('\u0000')
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun SettingAction(title: String, granted: Boolean?, onClick: () -> Unit) {
    SettingsRow(
        title = title,
        description = when (granted) {
            true -> "权限状态正常"
            false -> "需要前往系统设置完成"
            null -> "在系统设置中检查状态"
        },
        trailing = {
            StatusPill(
                text = when (granted) { true -> "已完成"; false -> "去设置"; null -> "检查" },
                positive = granted == true,
            )
        },
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun CategoryDialog(
    title: String,
    initialName: String,
    initialType: AccountingType,
    showType: Boolean,
    initialOrder: Int,
    initialActive: Boolean,
    initialColorHex: String?,
    initialEmoji: String?,
    onDismiss: () -> Unit,
    onSave: (String, AccountingType, Int, Boolean, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var type by remember { mutableStateOf(initialType) }
    var orderText by remember { mutableStateOf(initialOrder.toString()) }
    var active by remember { mutableStateOf(initialActive) }
    var colorHex by remember {
        mutableStateOf(initialColorHex ?: DefaultCategories.fallbackPalette.first())
    }
    var emoji by remember {
        mutableStateOf(initialEmoji ?: DefaultCategories.FALLBACK_EMOJI)
    }
    val selectedColor = parseHexColorOrNull(colorHex) ?: MaterialTheme.colorScheme.primary

    YanHuoDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 预览分类在列表里的实际样子
                    Surface(
                        modifier = Modifier.size(52.dp),
                        shape = RoundedCornerShape(19.dp),
                        color = selectedColor.pastelContainer(),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(emoji.ifBlank { DefaultCategories.FALLBACK_EMOJI }, fontSize = 24.sp)
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { value -> name = value },
                        label = { Text("分类名称") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { value -> emoji = value.take(4) },
                    label = { Text("图标 emoji") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text("分类颜色", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (candidate in DefaultCategories.fallbackPalette) {
                        ColorSwatch(
                            hex = candidate,
                            selected = candidate.equals(colorHex, ignoreCase = true),
                            onClick = { colorHex = candidate },
                        )
                    }
                }
                if (showType) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (candidate in listOf(AccountingType.EXPENSE, AccountingType.INCOME, AccountingType.TRANSFER)) {
                            FilterChip(
                                selected = type == candidate,
                                onClick = { type = candidate },
                                label = { Text(accountingTypeLabel(candidate)) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = orderText,
                    onValueChange = { value -> orderText = value },
                    label = { Text("排序") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", modifier = Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { value -> active = value })
                }
            }
        },
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(
                            name,
                            type,
                            orderText.toIntOrNull() ?: initialOrder,
                            active,
                            colorHex,
                            emoji.ifBlank { DefaultCategories.FALLBACK_EMOJI },
                        )
                    }
                },
            ) { Text("保存") }
        },
    )
}

@Composable
private fun ColorSwatch(hex: String, selected: Boolean, onClick: () -> Unit) {
    val color = parseHexColorOrNull(hex) ?: MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        modifier = Modifier.size(38.dp),
        shape = CircleShape,
        color = color,
        border = if (selected) {
            BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
        } else {
            null
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun PasswordDialog(title: String, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    YanHuoDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("至少 6 个字符") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(onClick = {
                if (password.length < 6) error = "密码至少需要 6 个字符" else onConfirm(password.toCharArray())
            }) { Text("确认") }
        },
    )
}
