package com.yanhuozhang.offline.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WechatOcrMerchantExtractorTest {
    // 坐标取自真机 1200×2608 京东支付成功页截图，按默认裁剪区（屏幕高度 10%–40%）换算。
    private val jingdongPage = listOf(
        OcrTextLine("京东", 549, 205, 651, 286),
        OcrTextLine("¥4.04", 416, 338, 780, 455),
        OcrTextLine("你有一个1元满减券待领取", 204, 679, 684, 745),
        OcrTextLine("领取后再邀1人变为2元满减", 204, 757, 708, 823),
        OcrTextLine("领取", 954, 703, 1128, 799),
    )

    @Test
    fun `extracts merchant above verified amount on real Jingdong page`() {
        val result = WechatOcrMerchantExtractor.extract(jingdongPage, expectedAmountCents = 404)

        assertEquals(OcrMerchantExtraction.Found("京东", matchedByAnchor = false), result)
    }

    @Test
    fun `reads yen sign misrecognized as letter Y`() {
        val lines = jingdongPage.map { line ->
            if (line.text == "¥4.04") line.copy(text = "Y4.04") else line
        }

        val result = WechatOcrMerchantExtractor.extract(lines, expectedAmountCents = 404)

        assertEquals(OcrMerchantExtraction.Found("京东", matchedByAnchor = false), result)
    }

    @Test
    fun `rejects merchant when recognized amount differs from accessibility amount`() {
        val lines = jingdongPage.map { line ->
            if (line.text == "¥4.04") line.copy(text = "¥4.84") else line
        }

        val result = WechatOcrMerchantExtractor.extract(lines, expectedAmountCents = 404)

        assertEquals(OcrMerchantExtraction.AmountMismatch(listOf(484L)), result)
    }

    @Test
    fun `gives up when no amount line is recognized`() {
        val lines = jingdongPage.filterNot { line -> line.text == "¥4.04" }

        val result = WechatOcrMerchantExtractor.extract(lines, expectedAmountCents = 404)

        assertEquals(OcrMerchantExtraction.AmountNotFound, result)
    }

    @Test
    fun `uses pay-to anchor when present`() {
        val lines = listOf(
            OcrTextLine("付款给 永辉超市", 300, 40, 900, 100),
            OcrTextLine("¥28.50", 400, 300, 800, 420),
        )

        val result = WechatOcrMerchantExtractor.extract(lines, expectedAmountCents = 2_850)

        assertEquals(OcrMerchantExtraction.Found("永辉超市", matchedByAnchor = true), result)
    }

    @Test
    fun `reads value beside a standalone label anchor`() {
        val lines = listOf(
            OcrTextLine("¥12.00", 400, 100, 800, 220),
            OcrTextLine("收款方", 40, 500, 200, 560),
            OcrTextLine("张三便利店", 600, 502, 1_100, 558),
        )

        val result = WechatOcrMerchantExtractor.extract(lines, expectedAmountCents = 1_200)

        assertEquals(OcrMerchantExtraction.Found("张三便利店", matchedByAnchor = true), result)
    }

    @Test
    fun `promotion text mentioning merchant is not treated as an anchor`() {
        val lines = listOf(
            OcrTextLine("商家优惠券已发放", 300, 0, 900, 50),
            OcrTextLine("京东", 549, 205, 651, 286),
            OcrTextLine("¥4.04", 416, 338, 780, 455),
        )

        val result = WechatOcrMerchantExtractor.extract(lines, expectedAmountCents = 404)

        assertEquals(OcrMerchantExtraction.Found("京东", matchedByAnchor = false), result)
    }

    @Test
    fun `does not invent merchant from success marker or completion text`() {
        val lines = listOf(
            OcrTextLine("支付成功", 400, 200, 800, 280),
            OcrTextLine("¥4.04", 416, 338, 780, 455),
            OcrTextLine("完成", 500, 900, 700, 960),
        )

        val result = WechatOcrMerchantExtractor.extract(lines, expectedAmountCents = 404)

        assertEquals(OcrMerchantExtraction.MerchantNotFound, result)
    }

    @Test
    fun `accepts page showing both original and paid price`() {
        val lines = listOf(
            OcrTextLine("京东", 549, 205, 651, 286),
            OcrTextLine("¥9.90", 416, 338, 780, 455),
            OcrTextLine("¥10.90", 500, 470, 700, 520),
        )

        val result = WechatOcrMerchantExtractor.extract(lines, expectedAmountCents = 990)

        assertEquals(OcrMerchantExtraction.Found("京东", matchedByAnchor = false), result)
    }

    @Test
    fun `parses amount variants produced by OCR`() {
        assertEquals(404L, WechatOcrMerchantExtractor.amountOf("￥ 4.04"))
        assertEquals(404L, WechatOcrMerchantExtractor.amountOf("¥4,04"))
        assertEquals(123_400L, WechatOcrMerchantExtractor.amountOf("¥1,234.00"))
        assertEquals(1_000L, WechatOcrMerchantExtractor.amountOf("¥10"))
        assertEquals(null, WechatOcrMerchantExtractor.amountOf("1元满减"))
    }
}
