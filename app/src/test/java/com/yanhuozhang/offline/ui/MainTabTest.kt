package com.yanhuozhang.offline.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MainTabTest {
    @Test
    fun `main navigation keeps four content tabs around the add action`() {
        assertEquals(
            listOf("首页", "账单", "待确认", "统计"),
            MainTab.entries.map { tab -> tab.label },
        )
    }
}
