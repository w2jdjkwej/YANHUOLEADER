package com.yanhuozhang.offline.settings

import com.yanhuozhang.offline.core.ScreenBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantOcrSettingsTest {
    @Test
    fun `default crop covers merchant and amount on real device`() {
        val crop = MerchantOcrSettings().cropBounds(width = 1_200, height = 2_608)

        assertEquals(ScreenBounds(0, 260, 1_200, 1_043), crop)
        // 真机样本：商家名 465–546，金额 576–750。
        assertTrue(crop.top <= 465 && crop.bottom >= 750)
    }

    @Test
    fun `invalid crop falls back to defaults`() {
        val crop = MerchantOcrSettings(cropTopPercent = 60, cropBottomPercent = 20)
            .cropBounds(width = 1_200, height = 2_608)

        assertEquals(MerchantOcrSettings().cropBounds(width = 1_200, height = 2_608), crop)
    }

    @Test
    fun `validates crop percentages`() {
        assertTrue(MerchantOcrSettings.isValidCrop(0, 100))
        assertFalse(MerchantOcrSettings.isValidCrop(40, 40))
        assertFalse(MerchantOcrSettings.isValidCrop(-1, 40))
        assertFalse(MerchantOcrSettings.isValidCrop(10, 101))
    }
}
