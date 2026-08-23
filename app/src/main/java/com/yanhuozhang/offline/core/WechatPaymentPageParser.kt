package com.yanhuozhang.offline.core

import kotlin.math.abs

data class ScreenTextNode(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickableContainer: ScreenBounds? = null,
)

data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class PaymentPageObservation(
    val amountCents: Long,
    val counterparty: String?,
)

object WechatPaymentPageParser {
    private val amountPattern = Regex("^[¥￥]\\s*(\\d+(?:\\.\\d{1,2})?)$")
    private val amountInTextPattern = Regex("[¥￥]\\s*(\\d+(?:\\.\\d{1,2})?)")
    private val completionActions = setOf("完成", "返回商家")
    private val excludedText = setOf("完成", "返回商家", "去打卡")

    fun parseNodes(nodes: List<ScreenTextNode>): PaymentPageObservation? {
        val visibleNodes = nodes.map { node -> node.copy(text = node.text.trim()) }
            .filter { node -> node.text.isNotEmpty() }
        if (visibleNodes.none { node -> isSuccessMarker(node.text) }) {
            return null
        }
        if (visibleNodes.none { node -> containsCompletionAction(node.text) }) {
            return null
        }

        // 优先使用整段文本就是金额的叶子节点。只有在完全找不到时，才退回扫描长文本里的金额，
        // 否则页面上的优惠券、立减等附带金额会让候选金额出现多个，导致整页无法识别。
        val exactAmountNodes = visibleNodes.mapNotNull { node -> exactAmount(node) }
        val amountNodes = exactAmountNodes.ifEmpty {
            visibleNodes.flatMap { node -> amountsInText(node) }
        }
        if (amountNodes.isEmpty()) {
            return null
        }
        val distinctAmountCents = amountNodes.map { pair -> pair.second }.distinct()
        if (distinctAmountCents.size != 1) {
            return null
        }

        val amountCents = distinctAmountCents.single()
        // 同一个金额可能同时出现在叶子节点和父容器的 contentDescription 中。
        // 选择最小的有效边界，避免父容器的坐标干扰商家相对位置判断。
        val amountNode = amountNodes
            .map { pair -> pair.first }
            .filter { node -> node.right > node.left && node.bottom > node.top }
            .minWithOrNull(
                compareBy<ScreenTextNode> { node -> boundsArea(node) }
                    .thenBy { node -> node.top },
            )
            ?: amountNodes.minBy { pair -> pair.first.top }.first
        val amountCenter = (amountNode.left + amountNode.right) / 2
        val maximumCenterDistance = maxOf(amountNode.right - amountNode.left, 320)
        // 商家名与金额的行高常有几像素重叠，要求完全分离会漏掉商家；这里留出一点容差，
        // 同时排除同时包住金额的父容器。
        val topOverlapTolerance = (amountNode.bottom - amountNode.top) / 4
        val counterparty = visibleNodes.asSequence()
            .filter { node -> node.top < amountNode.top }
            .filter { node -> node.bottom <= amountNode.top + topOverlapTolerance }
            .filter { node -> node.text.length in 1..40 }
            .filter { node -> isCounterpartyCandidate(node.text) }
            .filter { node ->
                val nodeCenter = (node.left + node.right) / 2
                abs(nodeCenter - amountCenter) <= maximumCenterDistance
            }
            .minByOrNull { node -> amountNode.top - node.bottom }
            ?.text
            ?: visibleNodes.asSequence()
                .mapNotNull { node -> extractCounterpartyFromCombinedText(node.text, amountCents) }
                .firstOrNull()

        return PaymentPageObservation(
            amountCents = amountCents,
            counterparty = counterparty,
        )
    }

    fun parseRaw(raw: RawObservation): ParsedObservation? {
        if (raw.source != EventSource.WECHAT_PAYMENT_PAGE) {
            return null
        }
        if (raw.content.lineSequence().none { line -> line.trim() == "支付成功" }) {
            return null
        }

        val amountCents = MoneyParser.parseCents(raw.content) ?: return null
        val counterparty = raw.title?.trim()?.takeIf { value -> value.isNotEmpty() }
        return ParsedObservation(
            source = raw.source,
            sourceEventId = raw.sourceEventId,
            rawText = raw.content,
            amountCents = amountCents,
            direction = TransactionDirection.OUT,
            kind = ObservationKind.PAYMENT,
            transactionTimeMillis = raw.observedAtMillis,
            observedAtMillis = raw.observedAtMillis,
            canAutoRecord = counterparty != null,
            counterparty = counterparty,
        )
    }

    private fun isCounterpartyCandidate(text: String): Boolean {
        if (isSuccessMarker(text) || text in excludedText || amountPattern.matches(text)) {
            return false
        }
        if (text.contains("行动") || text.startsWith("你光盘")) {
            return false
        }
        return true
    }

    private fun boundsArea(node: ScreenTextNode): Long {
        val width = (node.right - node.left).toLong()
        val height = (node.bottom - node.top).toLong()
        return width * height
    }

    private fun containsCompletionAction(text: String): Boolean {
        return completionActions.any { action -> text.contains(action) }
    }

    private fun exactAmount(node: ScreenTextNode): Pair<ScreenTextNode, Long>? {
        val match = amountPattern.matchEntire(node.text) ?: return null
        val cents = MoneyParser.parseUserInputCents(match.groupValues[1]) ?: return null
        return node to cents
    }

    private fun amountsInText(node: ScreenTextNode): List<Pair<ScreenTextNode, Long>> {
        return amountInTextPattern.findAll(node.text).mapNotNull { match ->
            val cents = MoneyParser.parseUserInputCents(match.groupValues[1]) ?: return@mapNotNull null
            node.copy(text = match.value.trim()) to cents
        }.toList()
    }

    private fun extractCounterpartyFromCombinedText(text: String, amountCents: Long): String? {
        val amountMatch = amountInTextPattern.findAll(text).firstOrNull { match ->
            MoneyParser.parseUserInputCents(match.groupValues[1]) == amountCents
        } ?: return null
        val textBeforeAmount = text.substring(0, amountMatch.range.first)
        val candidate = textBeforeAmount.lineSequence()
            .map { line -> cleanCounterpartyText(line) }
            .lastOrNull { value -> value != null }
            ?: cleanCounterpartyText(textBeforeAmount.substringAfterLast("支付成功"))
            ?: cleanCounterpartyText(textBeforeAmount.substringAfterLast("付款成功"))
        return candidate
    }

    private fun cleanCounterpartyText(value: String): String? {
        var candidate = value.trim().trim('，', '。', ':', '：')
        val prefixes = listOf(
            "交易对象：", "交易对象:", "收款方：", "收款方:", "商家：", "商家:",
            "您已付款给", "你已付款给", "已付款给", "付款给", "支付给",
        )
        for (prefix in prefixes) {
            if (candidate.startsWith(prefix)) {
                candidate = candidate.removePrefix(prefix).trim()
                break
            }
        }
        if (candidate.length !in 1..40 || !isCounterpartyCandidate(candidate)) {
            return null
        }
        return candidate
    }

    private fun isSuccessMarker(text: String): Boolean {
        val normalizedText = text.filterNot { character -> character.isWhitespace() }
        return normalizedText.contains("支付成功") || normalizedText.contains("付款成功")
    }
}
