package com.yanhuozhang.offline.core

data class LocalCategoryMatch(
    val categoryName: String,
    val keyword: String,
)

object LocalCategoryClassifier {
    private data class KeywordRule(
        val categoryName: String,
        val keywords: List<String>,
    )

    private val rules = listOf(
        KeywordRule(
            categoryName = "餐饮",
            keywords = listOf(
                "餐饮", "自助餐", "火锅", "烧烤", "烤肉", "饭店", "餐厅", "酒楼", "食堂",
                "小吃", "快餐", "外卖", "面馆", "米线", "麻辣烫", "咖啡", "茶饮", "奶茶",
                "甜品", "蛋糕", "烘焙", "海底捞", "肯德基", "麦当劳", "必胜客", "星巴克",
                "瑞幸", "蜜雪冰城", "饿了么", "美团外卖",
            ),
        ),
        KeywordRule(
            categoryName = "交通",
            keywords = listOf(
                "高德打车", "曹操出行", "t3出行", "滴滴", "打车", "出租车", "网约车",
                "轨道交通", "地铁", "公交", "客运", "铁路", "高铁", "火车票", "航空",
                "机票", "加油站", "中石油", "中石化", "充电桩", "停车", "高速公路",
                "高速通行", "轮渡",
            ),
        ),
        KeywordRule(
            categoryName = "医疗",
            keywords = listOf(
                "人民医院", "中心医院", "中医院", "医院", "诊所", "卫生院", "药房", "药店",
                "医药", "体检", "牙科", "口腔", "眼科", "挂号", "康复", "医疗器械",
            ),
        ),
        KeywordRule(
            categoryName = "学习",
            keywords = listOf(
                "大学", "学院", "学校", "幼儿园", "教育", "培训", "书店", "图书", "学费",
                "考试", "考务", "课程", "文具", "留学",
            ),
        ),
        KeywordRule(
            categoryName = "住房",
            keywords = listOf(
                "房租", "物业", "供暖", "燃气", "水务", "电力", "宽带", "家政", "装修",
                "装潢", "酒店", "宾馆", "民宿", "公寓",
            ),
        ),
        KeywordRule(
            categoryName = "日用品",
            keywords = listOf(
                "日用品", "便利店", "超市", "生鲜", "菜市场", "农贸市场", "果蔬", "粮油",
                "盒马", "永辉", "沃尔玛", "山姆会员", "屈臣氏", "名创优品", "洗护",
                "家居生活",
            ),
        ),
        KeywordRule(
            categoryName = "娱乐",
            keywords = listOf(
                "电影院", "影城", "电影票", "ktv", "剧本杀", "密室逃脱", "电竞", "网吧",
                "游戏", "棋牌", "游乐园", "主题乐园", "迪士尼", "演出票", "音乐会",
                "健身房", "体育馆",
            ),
        ),
        KeywordRule(
            categoryName = "购物",
            keywords = listOf(
                "淘宝", "天猫", "京东", "拼多多", "唯品会", "苏宁易购", "购物中心", "商场",
                "百货", "服饰", "服装", "鞋服", "箱包", "珠宝", "数码", "手机专卖",
                "电脑", "电器",
            ),
        ),
        KeywordRule(
            categoryName = "人情往来",
            keywords = listOf("份子钱", "随礼", "礼金", "慰问金", "捐赠", "红包"),
        ),
    )

    fun classify(counterparty: String?, note: String? = null): LocalCategoryMatch? {
        val normalizedCounterparty = CounterpartyNormalizer.normalize(counterparty)
        val normalizedNote = CounterpartyNormalizer.normalize(note)
        if (normalizedCounterparty.isEmpty() && normalizedNote.isEmpty()) {
            return null
        }
        val searchableText = normalizedCounterparty + normalizedNote
        for (rule in rules) {
            for (keyword in rule.keywords) {
                val normalizedKeyword = CounterpartyNormalizer.normalize(keyword)
                if (searchableText.contains(normalizedKeyword)) {
                    return LocalCategoryMatch(rule.categoryName, keyword)
                }
            }
        }
        return null
    }
}
