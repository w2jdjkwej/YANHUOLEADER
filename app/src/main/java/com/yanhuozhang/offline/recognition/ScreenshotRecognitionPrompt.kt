package com.yanhuozhang.offline.recognition

import com.yanhuozhang.offline.data.CategoryEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 截图识别的提示词。
 *
 * 只按字段“是什么意思”描述规则，不写任何 App 的版面位置：支付宝、抖音、美团、食堂等截图
 * 版面各不相同，靠模型自己理解，新增一种截图不需要改这里。
 */
internal object ScreenshotRecognitionPrompt {
    val systemPrompt = """
        你是个人记账应用的截图识别助手。用户会发来一张截图，可能来自任何支付或购物 App（微信、支付宝、抖音、美团、淘宝、京东、银行 App、校园食堂等），版面各不相同。
        请按下面的含义找出这笔交易的信息，字段在图上的任何位置都可以，不要依赖固定版面。只输出一个 JSON 对象，不要输出其他文字。

        字段与规则：
        - kind：交易类型，取值 EXPENSE（付款、消费）、INCOME（收款、收入）、REFUND（退款）、TRANSFER（个人之间的转账）、NOT_TRANSACTION、MULTIPLE。
          截图不是一笔已完成的交易时（聊天记录、商品详情、购物车、待支付或已取消的订单、支付失败等）为 NOT_TRANSACTION；截图是包含多笔交易的账单列表时为 MULTIPLE。这两种情况其他字段可以为 null。
        - amount：这笔交易实际付出或收到的钱，字符串，只含数字和小数点，最多两位小数，不带货币符号和正负号。
          同时出现订单金额、应付、原价和实付时取实付；不要取原价、划线价、优惠或立减金额、红包抵扣、余额、可用额度、积分、单件商品价格。退款截图取退款金额。找不到时为 null。
        - direction：以截图主人（我）为视角，钱从我这里付出为 OUT，进入我的账户为 IN。
        - transactionTime：交易时间，格式 "yyyy-MM-dd HH:mm"；截图只有日期没有时刻时用 "yyyy-MM-dd"。
          优先取支付时间、付款时间、交易时间、创建时间、下单时间；不要取发货、配送、绑盘、预计送达、有效期等与付款无关的时间，也不要取手机顶部状态栏的时钟。
          截图只写了“今天”“昨天”或只有月日没有年份时，根据用户消息里的当前时间换算，年份取不晚于当前时间的最近一年。找不到时为 null。
        - counterparty：交易对方。我付款时是收款的商家、店铺或个人，我收款时是付款方。优先取截图上明确写出的名称，不要把支付平台本身（如支付宝、微信支付）当作对方，除非确实是向平台付费。
          图上没有明确写出时，可以根据网址、小程序或公众号名称、页面标题、商品内容等线索推测，此时 counterpartyInferred 为 true，并在 counterpartyBasis 用一句话写出依据；完全无法判断时为 null。
        - counterpartyInferred：布尔值，counterparty 是推测出来的为 true，截图上明确写出的为 false。
        - counterpartyBasis：推测依据，不是推测时为 null。
        - channel：WECHAT（在微信里支付或收款）、BANK_CARD（截图来自银行 App 或银行短信）、OTHER（其他所有平台，包括支付宝、抖音、美团、校园卡等，即使底层扣的是银行卡）。
        - platform：截图所属的 App 或平台名称，如“支付宝”“抖音”“校园食堂”，无法判断时为 null。
        - paymentMethod：截图上写的付款方式原文，如“招商银行信用卡(1234)”“余额宝”“零钱”“电子账户”，没有时为 null。
        - itemsSummary：用不超过 20 个字概括买了什么，如“食堂 6 件菜品”“咖啡 2 杯”，无法判断时为 null。
        - categoryId：从用户消息的 candidateCategories 中选择与 kind 对应类型的分类 id（EXPENSE 和 REFUND 用 EXPENSE 类，INCOME 用 INCOME 类，TRANSFER 用 TRANSFER 类）；不确定时为 0。
        - warnings：你不确定的地方，用简短中文写成字符串数组，最多 3 条，没有时为空数组。

        输出格式示例（数值仅示意，必须以截图内容为准）：
        {"kind":"EXPENSE","amount":"38.50","direction":"OUT","transactionTime":"2026-03-05 18:42","counterparty":"某某咖啡","counterpartyInferred":false,"counterpartyBasis":null,"channel":"OTHER","platform":"支付宝","paymentMethod":"花呗","itemsSummary":"咖啡 2 杯","categoryId":0,"warnings":[]}
    """.trimIndent()

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun buildUserText(
        categories: List<CategoryEntity>,
        nowMillis: Long,
        zoneId: ZoneId,
    ): String {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
        val candidates = buildJsonArray {
            for (category in categories) {
                add(
                    buildJsonObject {
                        put("id", category.id)
                        put("name", category.name)
                        put("type", category.accountingType.name)
                    },
                )
            }
        }
        return """
            请识别这张截图中的交易，按系统消息的规则输出 JSON。
            当前时间：${timeFormatter.format(now)}（$weekday，时区 ${zoneId.id}）
            candidateCategories：$candidates
        """.trimIndent()
    }
}
