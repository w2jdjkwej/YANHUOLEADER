package com.yanhuozhang.offline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WechatRedPacketParserTest {
    @Test
    fun `amount page deduplicates repeated representations of the same amount`() {
        val result = WechatRedPacketParser.parseAmountPage(
            listOf(
                node("发红包"),
                node("金额0.01¥"),
                node("0.01"),
                node("¥0.01"),
                node("塞钱进红包"),
            ),
        )

        assertEquals(1L, result?.amountCents)
    }

    @Test
    fun `amount page rejects different simultaneous currency amounts`() {
        val result = WechatRedPacketParser.parseAmountPage(
            listOf(
                node("发红包"),
                node("¥0.01"),
                node("¥0.02"),
                node("塞钱进红包"),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `amount page requires exact red packet markers`() {
        val result = WechatRedPacketParser.parseAmountPage(
            listOf(
                node("金额0.01¥"),
                node("¥0.01"),
                node("塞钱进红包"),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `chat page returns outgoing red packet card multiset`() {
        val card = ScreenBounds(276, 1_572, 1_026, 1_831)
        val result = WechatRedPacketParser.parseChatPage(
            nodes = chatMarkers() + listOf(
                node("恭喜发财，大吉大利", card, top = 1_633),
                node("微信红包", card, top = 1_758),
            ),
            screenWidth = 1_200,
        )

        assertEquals(
            mapOf("恭喜发财，大吉大利\u001f微信红包" to 1),
            result?.outgoingCards,
        )
    }

    @Test
    fun `chat page counts repeated outgoing cards with the same text`() {
        val firstCard = ScreenBounds(276, 900, 1_026, 1_159)
        val secondCard = ScreenBounds(276, 1_572, 1_026, 1_831)
        val result = WechatRedPacketParser.parseChatPage(
            nodes = chatMarkers() + listOf(
                node("恭喜发财，大吉大利", firstCard, top = 950),
                node("微信红包", firstCard, top = 1_050),
                node("恭喜发财，大吉大利", secondCard, top = 1_633),
                node("微信红包", secondCard, top = 1_758),
            ),
            screenWidth = 1_200,
        )

        assertEquals(
            mapOf("恭喜发财，大吉大利\u001f微信红包" to 2),
            result?.outgoingCards,
        )
    }

    @Test
    fun `chat page excludes left aligned incoming red packet card`() {
        val incomingCard = ScreenBounds(174, 900, 924, 1_159)
        val result = WechatRedPacketParser.parseChatPage(
            nodes = chatMarkers() + listOf(
                node("恭喜发财，大吉大利", incomingCard, top = 950),
                node("微信红包", incomingCard, top = 1_050),
            ),
            screenWidth = 1_200,
        )

        assertNotNull(result)
        assertEquals(emptyMap<String, Int>(), result?.outgoingCards)
    }

    @Test
    fun `receipt text alone is not a sent red packet card`() {
        val result = WechatRedPacketParser.parseChatPage(
            nodes = chatMarkers() + node("麦兜领取了你的红包"),
            screenWidth = 1_200,
        )

        assertNotNull(result)
        assertEquals(emptyMap<String, Int>(), result?.outgoingCards)
    }

    @Test
    fun `non chat page does not produce a baseline`() {
        val result = WechatRedPacketParser.parseChatPage(
            nodes = listOf(node("微信红包")),
            screenWidth = 1_200,
        )

        assertNull(result)
    }

    private fun chatMarkers(): List<ScreenTextNode> = listOf(
        node("聊天信息"),
        node("更多功能按钮，已折叠"),
    )

    private fun node(
        text: String,
        container: ScreenBounds? = null,
        top: Int = 0,
    ) = ScreenTextNode(
        text = text,
        left = 0,
        top = top,
        right = 0,
        bottom = top + 50,
        clickableContainer = container,
    )
}
