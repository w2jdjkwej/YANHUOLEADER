package com.yanhuozhang.offline.core

data class RedPacketAmountPage(
    val amountCents: Long,
)

data class RedPacketChatPage(
    val outgoingCards: Map<String, Int>,
)

object WechatRedPacketParser {
    private const val SENT_MARKER = "微信红包已发送"
    private const val CARD_TEXT_SEPARATOR = "\u001f"
    private val prefixCurrencyPattern = Regex("^[¥￥]\\s*(\\d+(?:\\.\\d{1,2})?)$")
    private val suffixCurrencyPattern = Regex("^(?:金额)?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*[¥￥]$")

    fun parseAmountPage(nodes: List<ScreenTextNode>): RedPacketAmountPage? {
        val texts = nodes.map { node -> node.text.trim() }
            .filter { text -> text.isNotEmpty() }
        if ("发红包" !in texts || "塞钱进红包" !in texts) {
            return null
        }

        val amounts = texts.mapNotNull(::parseCurrencyCents)
            .filter { amountCents -> amountCents > 0 }
            .toSet()
        if (amounts.size != 1) {
            return null
        }
        return RedPacketAmountPage(amountCents = amounts.single())
    }

    fun parseChatPage(
        nodes: List<ScreenTextNode>,
        screenWidth: Int,
    ): RedPacketChatPage? {
        if (screenWidth <= 0) {
            return null
        }
        val texts = nodes.map { node -> node.text.trim() }
        val hasChatInformation = texts.any { text -> text == "聊天信息" }
        val hasMoreButton = texts.any { text -> text.startsWith("更多功能按钮") }
        if (!hasChatInformation || !hasMoreButton) {
            return null
        }

        val outgoingFingerprints = nodes.asSequence()
            .filter { node -> node.text.isNotBlank() && node.clickableContainer != null }
            .groupBy { node -> requireNotNull(node.clickableContainer) }
            .filter { (bounds, cardNodes) ->
                isOutgoing(bounds, screenWidth) &&
                    cardNodes.any { node -> node.text.trim() == "微信红包" }
            }
            .values
            .map { cardNodes ->
                cardNodes.sortedWith(compareBy<ScreenTextNode> { node -> node.top }.thenBy { node -> node.left })
                    .joinToString(CARD_TEXT_SEPARATOR) { node -> node.text.trim() }
            }
            .toList()

        return RedPacketChatPage(
            outgoingCards = outgoingFingerprints.groupingBy { fingerprint -> fingerprint }.eachCount(),
        )
    }

    fun parseRaw(raw: RawObservation): ParsedObservation? {
        if (raw.source != EventSource.WECHAT_PAYMENT_PAGE) {
            return null
        }
        if (raw.content.lineSequence().none { line -> line.trim() == SENT_MARKER }) {
            return null
        }
        val amountCents = MoneyParser.parseCents(raw.content) ?: return null
        if (amountCents <= 0) {
            return null
        }
        return ParsedObservation(
            source = raw.source,
            sourceEventId = raw.sourceEventId,
            rawText = raw.content,
            amountCents = amountCents,
            direction = TransactionDirection.OUT,
            kind = ObservationKind.RED_PACKET,
            transactionTimeMillis = raw.observedAtMillis,
            observedAtMillis = raw.observedAtMillis,
            canAutoRecord = false,
            counterparty = null,
        )
    }

    private fun parseCurrencyCents(text: String): Long? {
        val match = prefixCurrencyPattern.matchEntire(text)
            ?: suffixCurrencyPattern.matchEntire(text)
            ?: return null
        return MoneyParser.parseUserInputCents(match.groupValues[1])
    }

    private fun isOutgoing(bounds: ScreenBounds, screenWidth: Int): Boolean {
        val minimumLeft = screenWidth * 20 / 100
        val minimumRight = screenWidth * 80 / 100
        return bounds.left >= minimumLeft && bounds.right >= minimumRight
    }
}
