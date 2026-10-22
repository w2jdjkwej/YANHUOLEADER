package com.yanhuozhang.offline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatPaymentPageParserTest {
    @Test
    fun `parses real Jingdong payment page captured on device`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/wechat/jd-success-20261014.xml"))
        val document = stream.use {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val elements = document.getElementsByTagName("node")
        val nodes = mutableListOf<ScreenTextNode>()
        val boundsPattern = Regex("\\d+")
        for (index in 0 until elements.length) {
            val element = elements.item(index) as org.w3c.dom.Element
            val coordinates = boundsPattern.findAll(element.getAttribute("bounds")).map { it.value.toInt() }.toList()
            val text = element.getAttribute("text")
            val description = element.getAttribute("content-desc")
            for (value in listOf(text, description)) {
                if (value.isNotBlank()) {
                    nodes.add(ScreenTextNode(value, coordinates[0], coordinates[1], coordinates[2], coordinates[3]))
                }
            }
        }

        val observation = WechatPaymentPageParser.parseNodes(nodes)

        assertEquals(990L, observation?.amountCents)
        assertEquals("京东", observation?.counterparty)
    }

    @Test
    fun `incomplete success page can be retried without recording it`() {
        val nodes = listOf(ScreenTextNode("微信支付 成功", 0, 192, 1_200, 273))

        assertTrue(WechatPaymentPageParser.containsPaymentSuccessMarker(nodes))
        assertNull(WechatPaymentPageParser.parseNodes(nodes))
    }

    @Test
    fun `ordinary wechat page does not trigger payment retries`() {
        val nodes = listOf(
            ScreenTextNode("新天地超市", 0, 192, 1_200, 273),
            ScreenTextNode("正在付款", 0, 300, 1_200, 400),
        )

        assertFalse(WechatPaymentPageParser.containsPaymentSuccessMarker(nodes))
        assertNull(WechatPaymentPageParser.parseNodes(nodes))
    }

    @Test
    fun `extracts counterparty and cents from supplied success page`() {
        val result = WechatPaymentPageParser.parseNodes(
            listOf(
                ScreenTextNode("支付成功", 360, 120, 600, 200),
                ScreenTextNode("吉林大学", 390, 420, 570, 490),
                ScreenTextNode("￥10.00", 300, 520, 650, 650),
                ScreenTextNode("校园“光盘”行动", 180, 760, 600, 830),
                ScreenTextNode("你光盘，我捐餐，一起做好事", 180, 840, 670, 900),
                ScreenTextNode("去打卡", 720, 770, 890, 850),
                ScreenTextNode("完成", 400, 1780, 560, 1860),
            ),
        )

        assertEquals(1_000L, result?.amountCents)
        assertEquals("吉林大学", result?.counterparty)
    }

    @Test
    fun `extracts counterparty from success page with return to merchant action`() {
        val result = WechatPaymentPageParser.parseNodes(
            listOf(
                ScreenTextNode("微信支付 成功", 350, 120, 610, 200),
                ScreenTextNode("京东", 405, 350, 555, 420),
                ScreenTextNode("￥99.00", 270, 445, 690, 575),
                ScreenTextNode("你有一个1元满减券待领取", 80, 680, 650, 750),
                ScreenTextNode("领取", 750, 680, 890, 760),
                ScreenTextNode("返回商家", 330, 1_720, 630, 1_830),
            ),
        )

        assertEquals(9_900L, result?.amountCents)
        assertEquals("京东", result?.counterparty)
    }

    @Test
    fun `uses the leaf amount node when the same amount is also exposed by a broad parent`() {
        val result = WechatPaymentPageParser.parseNodes(
            listOf(
                ScreenTextNode("支付成功", 360, 120, 600, 200),
                ScreenTextNode("炸货铺", 390, 420, 570, 490),
                ScreenTextNode("￥21.00", 0, 0, 1_200, 2_608),
                ScreenTextNode("￥21.00", 300, 520, 650, 650),
                ScreenTextNode("完成", 400, 1_780, 560, 1_860),
            ),
        )

        assertEquals(2_100L, result?.amountCents)
        assertEquals("炸货铺", result?.counterparty)
    }

    @Test
    fun extractsMerchantFromObservedJingdongSuccessPageNodes() {
        val result = WechatPaymentPageParser.parseNodes(
            listOf(
                ScreenTextNode("支付成功", 0, 192, 1_200, 273),
                ScreenTextNode("京东", 549, 465, 651, 546),
                ScreenTextNode("￥19.80", 386, 576, 815, 750),
                ScreenTextNode("你有一个1元满减券待领取", 204, 939, 906, 1_005),
                ScreenTextNode("领取", 954, 963, 1_128, 1_059),
                ScreenTextNode("返回商家", 324, 2_128, 876, 2_272),
            ),
        )

        assertEquals(1_980L, result?.amountCents)
        assertEquals("京东", result?.counterparty)
    }

    @Test
    fun `extracts merchant when Lite page combines text into one description`() {
        val result = WechatPaymentPageParser.parseNodes(
            listOf(
                ScreenTextNode(
                    text = "支付成功\n商家：炸货铺\n￥21.00\n返回商家",
                    left = 0,
                    top = 0,
                    right = 1_200,
                    bottom = 2_608,
                ),
            ),
        )

        assertEquals(2_100L, result?.amountCents)
        assertEquals("炸货铺", result?.counterparty)
    }

    @Test
    fun `ignores promotional amounts embedded in longer text`() {
        val result = WechatPaymentPageParser.parseNodes(
            listOf(
                ScreenTextNode("支付成功", 0, 192, 1_200, 273),
                ScreenTextNode("京东", 549, 465, 651, 546),
                ScreenTextNode("￥4.04", 386, 576, 815, 750),
                ScreenTextNode("本单已为你立减￥2.00", 204, 939, 906, 1_005),
                ScreenTextNode("返回商家", 324, 2_128, 876, 2_272),
            ),
        )

        assertEquals(404L, result?.amountCents)
        assertEquals("京东", result?.counterparty)
    }

    @Test
    fun `finds merchant whose line height slightly overlaps the amount`() {
        val result = WechatPaymentPageParser.parseNodes(
            listOf(
                ScreenTextNode("支付成功", 0, 192, 1_200, 273),
                ScreenTextNode("京东", 549, 465, 651, 590),
                ScreenTextNode("￥4.04", 386, 576, 815, 750),
                ScreenTextNode("返回商家", 324, 2_128, 876, 2_272),
            ),
        )

        assertEquals(404L, result?.amountCents)
        assertEquals("京东", result?.counterparty)
    }

    @Test
    fun `returns observation without counterparty when merchant is absent`() {
        val result = WechatPaymentPageParser.parseNodes(
            listOf(
                ScreenTextNode("支付成功", 360, 120, 600, 200),
                ScreenTextNode("￥10.00", 300, 520, 650, 650),
                ScreenTextNode("完成", 400, 1780, 560, 1860),
            ),
        )

        assertEquals(1_000L, result?.amountCents)
        assertNull(result?.counterparty)
    }

    @Test
    fun `ignores page without payment success marker`() {
        val result = WechatPaymentPageParser.parseNodes(
            listOf(
                ScreenTextNode("吉林大学", 390, 420, 570, 490),
                ScreenTextNode("￥10.00", 300, 520, 650, 650),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `ignores page without exact amount`() {
        val result = WechatPaymentPageParser.parseNodes(
            listOf(
                ScreenTextNode("支付成功", 360, 120, 600, 200),
                ScreenTextNode("吉林大学", 390, 420, 570, 490),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `rejects ambiguous pages with more than one exact amount`() {
        val result = WechatPaymentPageParser.parseNodes(
            listOf(
                ScreenTextNode("支付成功", 360, 120, 600, 200),
                ScreenTextNode("吉林大学", 390, 420, 570, 490),
                ScreenTextNode("￥10.00", 300, 520, 650, 650),
                ScreenTextNode("￥20.00", 300, 700, 650, 830),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `ignores historical detail page without completion action`() {
        val result = WechatPaymentPageParser.parseNodes(
            listOf(
                ScreenTextNode("支付成功", 360, 120, 600, 200),
                ScreenTextNode("吉林大学", 390, 420, 570, 490),
                ScreenTextNode("￥10.00", 300, 520, 650, 650),
                ScreenTextNode("交易详情", 360, 900, 600, 980),
            ),
        )

        assertNull(result)
    }
}
