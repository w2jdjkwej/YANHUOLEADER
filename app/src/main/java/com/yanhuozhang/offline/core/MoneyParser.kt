package com.yanhuozhang.offline.core

import java.math.BigDecimal

object MoneyParser {
    private val amountPattern = Regex(
        pattern = "(?:[¥￥]\\s*)(\\d+(?:\\.\\d{1,2})?)|" +
            "(\\d+(?:\\.\\d{1,2})?)\\s*元",
    )

    fun parseCents(text: String): Long? {
        val match = amountPattern.find(text) ?: return null
        val amountText = match.groups[1]?.value ?: match.groups[2]?.value ?: return null

        val endIndex = match.range.last + 1
        if (endIndex < text.length && text[endIndex] == '.') {
            return null
        }
        if (endIndex < text.length && text[endIndex].isDigit()) {
            return null
        }

        return try {
            BigDecimal(amountText).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun parseUserInputCents(text: String): Long? {
        val normalized = text.trim()
        if (!Regex("\\d+(?:\\.\\d{1,2})?").matches(normalized)) {
            return null
        }

        return try {
            val cents = BigDecimal(normalized).movePointRight(2).longValueExact()
            cents.takeIf { value -> value > 0 }
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }
}
