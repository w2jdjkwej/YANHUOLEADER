package com.yanhuozhang.offline.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekSettingsTest {
    @Test
    fun `flash uses current API model identifier`() {
        assertEquals("deepseek-flash", DeepSeekModel.FLASH.apiValue)
        assertEquals("V4.1 Flash", DeepSeekModel.FLASH.displayName)
        assertEquals(DeepSeekModel.FLASH, DeepSeekModel.fromApiValue("deepseek-flash"))
        assertTrue(DeepSeekModel.isSupported("deepseek-flash"))
    }

    @Test
    fun `legacy flash setting and backups remain supported`() {
        assertEquals(DeepSeekModel.FLASH, DeepSeekModel.fromApiValue("deepseek-v4-flash"))
        assertTrue(DeepSeekModel.isSupported("deepseek-v4-flash"))
        assertFalse(DeepSeekModel.isSupported("unknown-model"))
    }
}