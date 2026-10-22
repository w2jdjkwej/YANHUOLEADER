package com.yanhuozhang.offline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanhuozhang.offline.data.LedgerRecordEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** 识别结束后显示在表单顶部的提示，表单关闭前一直保留。 */
internal sealed interface ScreenshotNotice {
    data class Filled(val warnings: List<String>) : ScreenshotNotice

    data class Failed(val reason: String) : ScreenshotNotice
}

/**
 * “从截图识别”按钮；识别进行中换成进度和取消按钮。
 *
 * 思考模式下识别可能要几十秒，所以显示已用时间，让用户知道没有卡住。
 */
@Composable
internal fun ScreenshotRecognitionEntry(
    available: Boolean,
    runningRequestId: Long?,
    onPick: () -> Unit,
    onCancel: () -> Unit,
) {
    if (runningRequestId == null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(
                onClick = onPick,
                enabled = available,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.DocumentScanner,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Text("从截图识别", modifier = Modifier.padding(start = 8.dp))
            }
            if (!available) {
                Text(
                    text = "需先在设置中启用 DeepSeek 并填写 API Key",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        return
    }

    var elapsedSeconds by remember(runningRequestId) { mutableIntStateOf(0) }
    LaunchedEffect(runningRequestId) {
        while (true) {
            delay(1_000)
            elapsedSeconds += 1
        }
    }
    YanHuoPanel(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Column(
                modifier = Modifier.weight(1f).padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "正在识别截图…",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "思考模式可能需要几十秒 · 已用 $elapsedSeconds 秒",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onCancel) {
                Text("取消")
            }
        }
    }
}

@Composable
internal fun ScreenshotNoticePanel(notice: ScreenshotNotice) {
    val (containerColor, contentColor) = when (notice) {
        is ScreenshotNotice.Filled ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        is ScreenshotNotice.Failed ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    YanHuoPanel(modifier = Modifier.fillMaxWidth(), containerColor = containerColor) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (notice) {
                is ScreenshotNotice.Filled -> {
                    Text(
                        text = "已根据截图填写，请核对后保存",
                        color = contentColor,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    for (warning in notice.warnings) {
                        Text(
                            text = "· $warning",
                            color = contentColor,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                is ScreenshotNotice.Failed -> {
                    Text(
                        text = "截图识别未完成",
                        color = contentColor,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = notice.reason,
                        color = contentColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PossibleDuplicatePanel(records: List<LedgerRecordEntity>) {
    val formatter = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }
    val zone = remember { ZoneId.systemDefault() }
    YanHuoPanel(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "可能已经记过这笔",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.titleSmall,
            )
            for (record in records) {
                val time = formatter.format(Instant.ofEpochMilli(record.transactionTimeMillis).atZone(zone))
                val description = record.counterparty?.takeIf { text -> text.isNotBlank() }
                    ?: record.note.takeIf { text -> text.isNotBlank() }
                    ?: paymentChannelLabel(record.paymentChannel)
                Row {
                    Text(
                        text = "$time · ${formatMoney(record.amountCents)}",
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = " · $description",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = "如果是同一笔，取消即可；确认不是再保存。",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
