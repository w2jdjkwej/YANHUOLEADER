package com.yanhuozhang.offline.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 分层原则：卡片不描边，靠 background 与 surface 的明度差分层。
// 底色用中性灰而不是强调色调出的淡绿——满屏浅绿会显得旧，
// 墨绿只保留在按钮、选中态、金额和英雄卡上，作为强调色出现。
internal val YanHuoLightColors = lightColorScheme(
    primary = Color(0xFF2D6A4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCEAD7),
    onPrimaryContainer = Color(0xFF0B3623),
    secondary = Color(0xFF52796F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE7E2),
    onSecondaryContainer = Color(0xFF1E3B33),
    tertiary = Color(0xFFB34464),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF65112F),
    background = Color(0xFFF3F4F6),
    onBackground = Color(0xFF15181C),
    surface = Color.White,
    onSurface = Color(0xFF15181C),
    surfaceVariant = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFF5F6870),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFB),
    surfaceContainer = Color(0xFFF1F2F4),
    surfaceContainerHigh = Color(0xFFEAECEF),
    surfaceContainerHighest = Color(0xFFE3E6EA),
    outline = Color(0xFF757C85),
    outlineVariant = Color(0xFFD6D9DE),
)

internal val YanHuoDarkColors = darkColorScheme(
    primary = Color(0xFF9AD5AE),
    onPrimary = Color(0xFF073921),
    primaryContainer = Color(0xFF24503A),
    onPrimaryContainer = Color(0xFFD3F4DD),
    secondary = Color(0xFFA8CEC0),
    onSecondary = Color(0xFF12382E),
    secondaryContainer = Color(0xFF283034),
    onSecondaryContainer = Color(0xFFD8EFE6),
    tertiary = Color(0xFFFFB1C3),
    onTertiary = Color(0xFF67112F),
    tertiaryContainer = Color(0xFF7E2948),
    onTertiaryContainer = Color(0xFFFFD9E2),
    background = Color(0xFF0E0F11),
    onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF17191C),
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF23262A),
    onSurfaceVariant = Color(0xFFB0B6BD),
    surfaceContainerLowest = Color(0xFF0A0B0D),
    surfaceContainerLow = Color(0xFF131518),
    surfaceContainer = Color(0xFF191B1F),
    surfaceContainerHigh = Color(0xFF202328),
    surfaceContainerHighest = Color(0xFF282C31),
    outline = Color(0xFF8A9199),
    outlineVariant = Color(0xFF2E3238),
)

/** 收支语义色。支出此前完全没有颜色，与收入无法一眼区分。 */
@Immutable
internal data class YanHuoMoneyColors(
    val income: Color,
    val expense: Color,
    val neutral: Color,
)

private val LightMoneyColors = YanHuoMoneyColors(
    income = Color(0xFF3E8E4E),
    expense = Color(0xFFC2504F),
    neutral = Color(0xFF5F6870),
)

private val DarkMoneyColors = YanHuoMoneyColors(
    income = Color(0xFF6FCB80),
    expense = Color(0xFFE08585),
    neutral = Color(0xFFB0B6BD),
)

internal val LocalYanHuoMoneyColors = staticCompositionLocalOf { LightMoneyColors }

/** 取当前主题下的收支语义色。 */
internal val moneyColors: YanHuoMoneyColors
    @Composable
    @ReadOnlyComposable
    get() = LocalYanHuoMoneyColors.current

// 金额使用等宽数字（tabular figures），滚动动画时数字宽度不跳动。
private const val TabularFigures = "tnum"

private val YanHuoTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 52.sp,
        lineHeight = 58.sp,
        fontWeight = FontWeight.ExtraBold,
        fontFeatureSettings = TabularFigures,
    ),
    displayMedium = TextStyle(
        fontSize = 44.sp,
        lineHeight = 50.sp,
        fontWeight = FontWeight.ExtraBold,
        fontFeatureSettings = TabularFigures,
    ),
    displaySmall = TextStyle(
        fontSize = 36.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.ExtraBold,
        fontFeatureSettings = TabularFigures,
    ),
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
)

private val YanHuoShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)

internal fun yanHuoColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) YanHuoDarkColors else YanHuoLightColors

internal fun yanHuoMoneyColors(darkTheme: Boolean): YanHuoMoneyColors =
    if (darkTheme) DarkMoneyColors else LightMoneyColors

@Composable
fun YanHuoTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()

    CompositionLocalProvider(
        LocalYanHuoMoneyColors provides yanHuoMoneyColors(darkTheme),
    ) {
        MaterialTheme(
            colorScheme = yanHuoColorScheme(darkTheme),
            typography = YanHuoTypography,
            shapes = YanHuoShapes,
            content = content,
        )
    }
}
