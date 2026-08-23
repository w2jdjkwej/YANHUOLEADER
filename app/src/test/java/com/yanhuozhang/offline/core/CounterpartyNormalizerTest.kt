package com.yanhuozhang.offline.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CounterpartyNormalizerTest {
    @Test
    fun `masked suffix and punctuation do not affect matching key`() {
        assertEquals("静至安然", CounterpartyNormalizer.normalize(" 静至安然（**兵） "))
    }

    @Test
    fun `full width latin characters are normalized`() {
        assertEquals("t3出行", CounterpartyNormalizer.normalize("Ｔ３ 出行"))
    }
}
