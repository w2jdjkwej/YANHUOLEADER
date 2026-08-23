package com.yanhuozhang.offline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalCategoryClassifierTest {
    @Test
    fun `restaurant keywords map to food`() {
        assertEquals(
            "餐饮",
            LocalCategoryClassifier.classify("满座儿餐饮服务（北京）有限公司")?.categoryName,
        )
        assertEquals("餐饮", LocalCategoryClassifier.classify("某某火锅店")?.categoryName)
    }

    @Test
    fun `ride hailing keywords map to transport`() {
        assertEquals("交通", LocalCategoryClassifier.classify("滴滴出行")?.categoryName)
        assertEquals("交通", LocalCategoryClassifier.classify("高德打车")?.categoryName)
    }

    @Test
    fun `jingdong maps to shopping`() {
        assertEquals("购物", LocalCategoryClassifier.classify("京东")?.categoryName)
    }

    @Test
    fun `generic payment text stays unclassified`() {
        assertNull(LocalCategoryClassifier.classify("微信"))
        assertNull(LocalCategoryClassifier.classify("某某有限公司"))
    }
}
