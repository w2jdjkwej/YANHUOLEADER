package com.yanhuozhang.offline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCategoriesTest {
    @Test
    fun `contains planned expense and income categories`() {
        val expenseNames = DefaultCategories.items
            .filter { it.accountingType == AccountingType.EXPENSE }
            .map { it.name }
        val incomeNames = DefaultCategories.items
            .filter { it.accountingType == AccountingType.INCOME }
            .map { it.name }

        assertEquals(
            listOf("未分类", "餐饮", "交通", "购物", "娱乐", "住房", "医疗", "学习", "日用品", "人情往来", "其他"),
            expenseNames,
        )
        assertEquals(listOf("工资", "奖金", "兼职", "红包", "报销", "其他"), incomeNames)
    }

    @Test
    fun `sort order is unique inside each accounting type`() {
        val groups = DefaultCategories.items.groupBy { it.accountingType }

        for (items in groups.values) {
            assertEquals(items.size, items.map { it.sortOrder }.distinct().size)
            assertTrue(items.all { it.isActive })
        }
    }
}
