package com.yanhuozhang.offline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyParserTest {
    @Test
    fun `parses yuan amount into integer cents`() {
        assertEquals(1480L, MoneyParser.parseCents("已支付￥14.80"))
    }

    @Test
    fun `parses whole yuan amount`() {
        assertEquals(870L, MoneyParser.parseCents("付款成功8.70元"))
        assertEquals(1200L, MoneyParser.parseCents("支出12元"))
    }

    @Test
    fun `rejects missing or over precise amount`() {
        assertNull(MoneyParser.parseCents("付款成功"))
        assertNull(MoneyParser.parseCents("支付￥1.234"))
    }

    @Test
    fun `parses positive manual input without currency marker`() {
        assertEquals(1480L, MoneyParser.parseUserInputCents("14.80"))
        assertEquals(1200L, MoneyParser.parseUserInputCents(" 12 "))
        assertNull(MoneyParser.parseUserInputCents("0"))
        assertNull(MoneyParser.parseUserInputCents("1.234"))
    }
}
