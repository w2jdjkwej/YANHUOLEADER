package com.yanhuozhang.offline.data

import com.yanhuozhang.offline.core.DefaultCategories

object DatabaseSeeder {
    suspend fun seedDefaultCategories(categoryDao: CategoryDao) {
        if (categoryDao.count() != 0) {
            return
        }

        val entities = DefaultCategories.items.map { category ->
            CategoryEntity(
                name = category.name,
                accountingType = category.accountingType,
                sortOrder = category.sortOrder,
                isActive = category.isActive,
                isSystem = true,
                colorHex = category.colorHex,
                emoji = category.emoji,
            )
        }
        categoryDao.insertAll(entities)
    }

    /**
     * 数据库 3→4 只加了空列，这里为升级上来的旧分类补上颜色和 emoji。
     * 预设分类按 (名称, 记账类型) 取原样式，用户自建分类按名称稳定取色。
     */
    suspend fun backfillCategoryStyles(categoryDao: CategoryDao) {
        for (category in categoryDao.getAll()) {
            if (category.colorHex != null && category.emoji != null) {
                continue
            }
            val preset = DefaultCategories.styleFor(category.name, category.accountingType)
            categoryDao.update(
                category.copy(
                    colorHex = category.colorHex
                        ?: preset?.colorHex
                        ?: DefaultCategories.fallbackColorHex(category.name),
                    emoji = category.emoji
                        ?: preset?.emoji
                        ?: DefaultCategories.FALLBACK_EMOJI,
                ),
            )
        }
    }
}
