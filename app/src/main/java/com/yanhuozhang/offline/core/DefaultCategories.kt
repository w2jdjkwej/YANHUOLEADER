package com.yanhuozhang.offline.core

import kotlin.math.abs

data class DefaultCategory(
    val name: String,
    val accountingType: AccountingType,
    val sortOrder: Int,
    val colorHex: String,
    val emoji: String,
    val isActive: Boolean = true,
)

/** 分类的展示身份：颜色贯穿列表行、饼图与趋势图，emoji 直接充当图标。 */
data class CategoryStyleSpec(
    val name: String,
    val colorHex: String,
    val emoji: String,
)

object DefaultCategories {
    /**
     * 用户自建分类没有预设样式时，从这里按名称稳定取色，
     * 保证同一个分类每次进入应用拿到的颜色一致。
     */
    val fallbackPalette: List<String> = listOf(
        "#66BB6A",
        "#26A69A",
        "#26C6DA",
        "#42A5F5",
        "#5C6BC0",
        "#7E57C2",
        "#AB47BC",
        "#EC407A",
        "#EF5350",
        "#FF7043",
        "#FFA726",
        "#8D6E63",
        "#78909C",
    )

    const val FALLBACK_EMOJI: String = "🏷️"

    val items: List<DefaultCategory> = buildList {
        addGroup(
            AccountingType.EXPENSE,
            style("未分类", "#78909C", "❔"),
            style("餐饮", "#EF5350", "🍜"),
            style("交通", "#42A5F5", "🚌"),
            style("购物", "#EC407A", "🛍️"),
            style("娱乐", "#7E57C2", "🎮"),
            style("住房", "#26A69A", "🏠"),
            style("医疗", "#26C6DA", "💊"),
            style("学习", "#FFA726", "📚"),
            style("日用品", "#66BB6A", "🧻"),
            style("人情往来", "#FF7043", "🎁"),
            style("其他", "#8D6E63", "📌"),
        )
        addGroup(
            AccountingType.INCOME,
            style("工资", "#43A047", "💰"),
            style("奖金", "#FFB300", "🏆"),
            style("兼职", "#26A69A", "💼"),
            style("红包", "#E53935", "🧧"),
            style("报销", "#5C6BC0", "🧾"),
            style("其他", "#8D6E63", "📌"),
        )
        addGroup(
            AccountingType.TRANSFER,
            style("AA", "#78909C", "🤝"),
            style("借出", "#FF7043", "📤"),
            style("借入", "#42A5F5", "📥"),
            style("还款", "#7E57C2", "💳"),
            style("房租", "#26A69A", "🏠"),
            style("代付", "#EC407A", "🫰"),
            style("资金流转", "#5C6BC0", "🔁"),
            style("待确认", "#9E9E9E", "⏳"),
        )
    }

    private val styleIndex: Map<Pair<String, AccountingType>, DefaultCategory> =
        items.associateBy { category -> category.name to category.accountingType }

    /** 预设分类的样式；用于旧数据库升级后的回填。 */
    fun styleFor(name: String, accountingType: AccountingType): DefaultCategory? =
        styleIndex[name to accountingType]

    /** 无预设样式时按名称稳定取色。 */
    fun fallbackColorHex(name: String): String =
        fallbackPalette[abs(name.hashCode()) % fallbackPalette.size]

    private fun style(name: String, colorHex: String, emoji: String) =
        CategoryStyleSpec(name, colorHex, emoji)

    private fun MutableList<DefaultCategory>.addGroup(
        accountingType: AccountingType,
        vararg specs: CategoryStyleSpec,
    ) {
        for ((index, spec) in specs.withIndex()) {
            add(
                DefaultCategory(
                    name = spec.name,
                    accountingType = accountingType,
                    sortOrder = index,
                    colorHex = spec.colorHex,
                    emoji = spec.emoji,
                ),
            )
        }
    }
}
