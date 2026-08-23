package com.yanhuozhang.offline.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yanhuozhang.offline.core.DefaultCategories
import com.yanhuozhang.offline.data.CategoryEntity
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

internal object YanHuoDimens {
    val ScreenPadding = 18.dp
    val PanelRadius = 26.dp
    val ItemRadius = 18.dp
}

// ---------------------------------------------------------------------------
// 颜色工具：与 Cashew 的 lightenPastel / darkenPastel 等价
// ---------------------------------------------------------------------------

internal fun Color.lightenPastel(amount: Float): Color = lerp(this, Color.White, amount)

internal fun Color.darkenPastel(amount: Float): Color = lerp(this, Color.Black, amount)

/** 在浅色主题下提亮、深色主题下压暗，用于分类色的容器底色。 */
@Composable
internal fun Color.pastelContainer(light: Float = 0.58f, dark: Float = 0.62f): Color =
    if (isSystemInDarkTheme()) darkenPastel(dark) else lightenPastel(light)

/** 分类色直接用在文字/图形上时，深色主题下需要提亮才够对比度。 */
@Composable
internal fun Color.onSurfaceReadable(): Color =
    if (isSystemInDarkTheme()) lightenPastel(0.24f) else darkenPastel(0.08f)

internal fun parseHexColorOrNull(hex: String?): Color? {
    val cleaned = hex?.removePrefix("#")?.removePrefix("0x") ?: return null
    val normalized = when (cleaned.length) {
        6 -> "FF$cleaned"
        8 -> cleaned
        else -> return null
    }
    return normalized.toLongOrNull(radix = 16)?.let { value -> Color(value.toInt()) }
}

// ---------------------------------------------------------------------------
// 分类身份：颜色 + emoji，界面各处共用同一来源
// ---------------------------------------------------------------------------

@Immutable
internal data class CategoryStyle(
    val color: Color,
    val emoji: String,
)

/**
 * 分类样式来自数据库字段。旧库升级后回填是异步的，
 * 所以这里对缺失值按名称做一次稳定兜底，避免闪一下灰色。
 */
internal fun categoryStyleOf(category: CategoryEntity?): CategoryStyle {
    if (category == null) {
        return CategoryStyle(Color(0xFF9E9E9E), "❔")
    }
    val color = parseHexColorOrNull(category.colorHex)
        ?: parseHexColorOrNull(DefaultCategories.fallbackColorHex(category.name))
        ?: Color(0xFF9E9E9E)
    return CategoryStyle(
        color = color,
        emoji = category.emoji?.takeIf { value -> value.isNotBlank() }
            ?: DefaultCategories.FALLBACK_EMOJI,
    )
}

/**
 * Cashew 风格的分类图标：圆角方块 + 分类色淡底 + emoji。
 * emoji 字号由 Dp 换算而来，不随系统字体缩放变化，避免撑破方块。
 */
@Composable
internal fun CategoryAvatar(
    category: CategoryEntity?,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
) {
    val style = categoryStyleOf(category)
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(size * 0.36f),
        color = style.color.pastelContainer(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = style.emoji,
                fontSize = with(LocalDensity.current) { (size * 0.46f).toSp() },
            )
        }
    }
}

/** 分类色的进度条，用于统计页的分类占比。 */
@Composable
internal fun CategoryProgressBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 7.dp,
) {
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "categoryProgress",
    )
    Box(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedFraction)
                .height(height)
                .clip(CircleShape)
                .background(color),
        )
    }
}

// ---------------------------------------------------------------------------
// 金额
// ---------------------------------------------------------------------------

private val amountFormat: DecimalFormat
    get() = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.CHINA))

/** 绝对值的千分位数字部分，如 "1,234.56"。 */
internal fun formatAmountDigits(cents: Long): String =
    amountFormat.format(BigDecimal(cents).movePointLeft(2).abs())

fun formatMoney(cents: Long): String =
    (if (cents < 0) "-" else "") + "¥" + formatAmountDigits(cents)

/**
 * 金额排版：货币符号比数字小一号且半透明，数字为主角。
 * 这是 Cashew 里最容易复用、观感提升也最明显的一处。
 */
@Composable
internal fun AmountText(
    cents: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = Color.Unspecified,
    showPlusSign: Boolean = false,
    symbolScale: Float = 0.66f,
    maxLines: Int = 1,
) {
    val sign = when {
        cents < 0 -> "-"
        showPlusSign && cents > 0 -> "+"
        else -> ""
    }
    val text = buildAnnotatedString {
        if (sign.isNotEmpty()) {
            append(sign)
        }
        withStyle(SpanStyle(fontSize = style.fontSize * symbolScale, fontWeight = FontWeight.Bold)) {
            append("¥")
        }
        append(formatAmountDigits(cents))
    }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style,
        maxLines = maxLines,
    )
}

/**
 * 金额变化时从旧值滚到新值（Cashew 的 countNumber）。
 * 用 0..1 的进度插值而不是直接动画 Long，避免大额时 Float 精度丢失。
 */
@Composable
internal fun animatedCents(target: Long, durationMillis: Int = 600): Long {
    val progress = remember { Animatable(1f) }
    var range by remember { mutableStateOf(target to target) }
    val (from, to) = range
    val current = from + ((to - from).toDouble() * progress.value).toLong()

    LaunchedEffect(target) {
        if (target != to) {
            range = current to target
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis, easing = FastOutSlowInEasing))
        }
    }
    return current
}

@Composable
internal fun AnimatedAmountText(
    cents: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displaySmall,
    color: Color = Color.Unspecified,
    showPlusSign: Boolean = false,
    symbolScale: Float = 0.62f,
) {
    AmountText(
        cents = animatedCents(cents),
        modifier = modifier,
        style = style,
        color = color,
        showPlusSign = showPlusSign,
        symbolScale = symbolScale,
    )
}

// ---------------------------------------------------------------------------
// 入场动画
// ---------------------------------------------------------------------------

/**
 * 记录哪些条目已经播过入场动画。
 * LazyColumn 会回收槽位，没有这个集合的话条目每次滚回可视区都会重播一次。
 */
@Stable
internal class AppearAnimationState {
    private val played = mutableSetOf<Any>()

    fun consumeShouldAnimate(key: Any): Boolean = played.add(key)
}

@Composable
internal fun rememberAppearAnimationState(): AppearAnimationState = remember { AppearAnimationState() }

/** 淡入 + 轻微上滑，对应 Cashew 的 fadeIn。 */
@Composable
internal fun FadeSlideIn(
    key: Any,
    state: AppearAnimationState,
    modifier: Modifier = Modifier,
    delayMillis: Int = 0,
    content: @Composable () -> Unit,
) {
    val shouldAnimate = remember(key) { state.consumeShouldAnimate(key) }
    if (!shouldAnimate) {
        Box(modifier = modifier) { content() }
        return
    }

    var visible by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (delayMillis > 0) {
            delay(delayMillis.toLong())
        }
        visible = true
    }
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing),
        label = "fadeSlideIn",
    )
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 22.dp.toPx()
        },
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// 容器
// ---------------------------------------------------------------------------

/**
 * 卡片不描边：背景是强调色调出的淡色，卡片贴近纯白，靠明度差分层。
 * 描边会让整屏看起来像线框图，是改版前最主要的观感问题。
 */
@Composable
internal fun YanHuoPanel(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    shape: Shape = RoundedCornerShape(YanHuoDimens.PanelRadius),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        content = content,
    )
}

/** 按下时轻微缩小，比默认水波纹更接近 Cashew 的手感。 */
@Composable
internal fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.975f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
internal fun YanHuoActionPanel(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    shape: Shape = RoundedCornerShape(YanHuoDimens.PanelRadius),
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .pressScale(interactionSource)
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        shape = shape,
        color = containerColor,
        content = content,
    )
}

@Composable
internal fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (action != null) {
            action()
        }
    }
}

@Composable
internal fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    positive: Boolean = false,
) {
    val background = if (positive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val foreground = if (positive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = background,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            color = foreground,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
internal fun SettingsRow(
    title: String,
    description: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (trailing != null) {
                trailing()
            }
        }
    }

    if (onClick == null) {
        YanHuoPanel(modifier = modifier, content = content)
    } else {
        YanHuoActionPanel(onClick = onClick, modifier = modifier, content = content)
    }
}

@Composable
internal fun YanHuoDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = modifier.fillMaxWidth().padding(horizontal = 22.dp).widthIn(max = 560.dp),
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                content()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}
