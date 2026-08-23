package com.yanhuozhang.offline.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ThemePaletteTest {
    @Test
    fun `green stays an accent while the background is neutral`() {
        assertEquals(Color(0xFF2D6A4F), YanHuoLightColors.primary)
        assertEquals(Color(0xFFF3F4F6), YanHuoLightColors.background)
        assertEquals(Color.White, YanHuoLightColors.surface)
    }

    @Test
    fun `cards separate from the background without borders`() {
        // 卡片不再描边，所以 surface 与 background 必须有可见明度差。
        assertNotEquals(YanHuoLightColors.background, YanHuoLightColors.surface)
        assertNotEquals(YanHuoDarkColors.background, YanHuoDarkColors.surface)
    }

    @Test
    fun `theme selection returns only the fixed application palettes`() {
        assertSame(YanHuoLightColors, yanHuoColorScheme(darkTheme = false))
        assertSame(YanHuoDarkColors, yanHuoColorScheme(darkTheme = true))
    }

    @Test
    fun `income and expense are distinguishable in both themes`() {
        for (darkTheme in listOf(false, true)) {
            val money = yanHuoMoneyColors(darkTheme)
            assertNotEquals(money.income, money.expense)
        }
    }
}
