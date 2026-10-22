package com.yanhuozhang.offline.core

import kotlin.math.abs

/** OCR 识别出的一行文字，坐标是裁剪图内的像素坐标。 */
data class OcrTextLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

sealed interface OcrMerchantExtraction {
    data class Found(val merchant: String, val matchedByAnchor: Boolean) : OcrMerchantExtraction

    /** OCR 读到了金额，但没有一个与无障碍抓到的金额一致，说明截到的不是这笔支付，整张图都不可信。 */
    data class AmountMismatch(val recognizedAmountsCents: List<Long>) : OcrMerchantExtraction

    /** 没读到金额就无法交叉校验，也无法按位置找商家，宁可放弃也不猜。 */
    data object AmountNotFound : OcrMerchantExtraction

    data object MerchantNotFound : OcrMerchantExtraction
}

object WechatOcrMerchantExtractor {
    // OCR 常把 ¥ 识别成 Y，把小数点识别成逗号；千分位逗号需要和小数逗号区分开。
    private val amountLinePattern = Regex("^[¥￥Yy]?(\\d{1,3}(?:,\\d{3})+|\\d+)(?:[.,](\\d{1,2}))?$")
    private val clockPattern = Regex("^\\d{1,2}[:：]\\d{2}$")

    // 这些锚点本身就说明后面是收款方，可以直接跟商家名。
    private val strongAnchors = listOf("您已付款给", "你已付款给", "已付款给", "付款给", "支付给")

    // 这些词也可能出现在“商家优惠”之类的文案里，只有单独成行或后面跟冒号时才当作锚点。
    private val labelAnchors = listOf("交易对象", "收款方", "商户全称", "商户名称", "商户", "商家")

    private const val MINIMUM_CENTER_DISTANCE = 320
    private const val MAXIMUM_GAP_IN_AMOUNT_HEIGHTS = 3

    fun extract(lines: List<OcrTextLine>, expectedAmountCents: Long): OcrMerchantExtraction {
        val visibleLines = lines.map { line -> line.copy(text = line.text.trim()) }
            .filter { line -> line.text.isNotEmpty() }
        val amountLines = visibleLines.mapNotNull { line ->
            amountOf(line.text)?.let { cents -> line to cents }
        }
        if (amountLines.isEmpty()) {
            return OcrMerchantExtraction.AmountNotFound
        }
        // 原价划线和实付可能同时出现，只要有一行与无障碍金额一致就算校验通过。
        val amountLine = amountLines.filter { pair -> pair.second == expectedAmountCents }
            .map { pair -> pair.first }
            .minByOrNull { line -> line.top }
            ?: return OcrMerchantExtraction.AmountMismatch(
                amountLines.map { pair -> pair.second }.distinct(),
            )

        merchantFromAnchor(visibleLines)?.let { merchant ->
            return OcrMerchantExtraction.Found(merchant, matchedByAnchor = true)
        }
        merchantAboveAmount(visibleLines, amountLine)?.let { merchant ->
            return OcrMerchantExtraction.Found(merchant, matchedByAnchor = false)
        }
        return OcrMerchantExtraction.MerchantNotFound
    }

    private fun merchantFromAnchor(lines: List<OcrTextLine>): String? {
        for (line in lines.sortedBy { candidate -> candidate.top }) {
            val compactText = line.text.filterNot { character -> character.isWhitespace() }
            val strongAnchor = strongAnchors.firstOrNull { anchor -> compactText.startsWith(anchor) }
            val labelAnchor = labelAnchors.firstOrNull { anchor ->
                compactText == anchor ||
                    compactText.startsWith("$anchor:") ||
                    compactText.startsWith("$anchor：")
            }
            val anchor = strongAnchor ?: labelAnchor ?: continue
            val remainder = line.text.trim()
                .removePrefixIgnoringSpaces(anchor)
                .trim()
                .trimStart(':', '：')
                .trim()
            val merchant = if (remainder.isNotEmpty()) {
                candidateOrNull(remainder)
            } else {
                valueLineForLabel(line, lines)?.let { valueLine -> candidateOrNull(valueLine.text) }
            }
            if (merchant != null) {
                return merchant
            }
        }
        return null
    }

    /** 标签和值分开成两行时，优先取同一行右侧的值，其次取正下方最近的一行。 */
    private fun valueLineForLabel(label: OcrTextLine, lines: List<OcrTextLine>): OcrTextLine? {
        val labelHeight = label.bottom - label.top
        val sameRow = lines.asSequence()
            .filter { line -> line !== label && line.left >= label.right }
            .filter { line -> abs(centerY(line) - centerY(label)) <= labelHeight / 2 }
            .minByOrNull { line -> line.left - label.right }
        if (sameRow != null) {
            return sameRow
        }
        return lines.asSequence()
            .filter { line -> line !== label && line.top >= label.bottom - labelHeight / 4 }
            .filter { line -> line.right > label.left && line.left < label.right + labelHeight * 4 }
            .minByOrNull { line -> line.top - label.bottom }
    }

    private fun merchantAboveAmount(lines: List<OcrTextLine>, amountLine: OcrTextLine): String? {
        val amountHeight = amountLine.bottom - amountLine.top
        val amountCenter = centerX(amountLine)
        val maximumCenterDistance = maxOf(amountLine.right - amountLine.left, MINIMUM_CENTER_DISTANCE)
        // 与节点解析一致：商家行和金额行的边界常有几像素重叠，留出四分之一行高的容差。
        val overlapTolerance = amountHeight / 4
        val maximumGap = amountHeight * MAXIMUM_GAP_IN_AMOUNT_HEIGHTS
        return lines.asSequence()
            .filter { line -> line !== amountLine }
            .filter { line -> line.top < amountLine.top && line.bottom <= amountLine.top + overlapTolerance }
            .filter { line -> amountLine.top - line.bottom <= maximumGap }
            .filter { line -> abs(centerX(line) - amountCenter) <= maximumCenterDistance }
            .sortedBy { line -> amountLine.top - line.bottom }
            .mapNotNull { line -> candidateOrNull(line.text) }
            .firstOrNull()
    }

    private fun candidateOrNull(text: String): String? {
        val trimmed = text.trim()
        val compactText = trimmed.filterNot { character -> character.isWhitespace() }
        if (amountOf(trimmed) != null || clockPattern.matches(compactText)) {
            return null
        }
        return WechatPaymentPageParser.cleanCounterpartyText(trimmed)
    }

    internal fun amountOf(text: String): Long? {
        val compactText = text.filterNot { character -> character.isWhitespace() }
        val match = amountLinePattern.matchEntire(compactText) ?: return null
        val integerPart = match.groupValues[1].replace(",", "")
        val decimalPart = match.groupValues[2]
        val amountText = if (decimalPart.isEmpty()) integerPart else "$integerPart.$decimalPart"
        return MoneyParser.parseUserInputCents(amountText)
    }

    private fun String.removePrefixIgnoringSpaces(prefix: String): String {
        var consumed = 0
        var index = 0
        while (index < length && consumed < prefix.length) {
            val character = this[index]
            if (character.isWhitespace()) {
                index += 1
                continue
            }
            if (character != prefix[consumed]) {
                return this
            }
            consumed += 1
            index += 1
        }
        return if (consumed == prefix.length) substring(index) else this
    }

    private fun centerX(line: OcrTextLine): Int = (line.left + line.right) / 2

    private fun centerY(line: OcrTextLine): Int = (line.top + line.bottom) / 2
}
