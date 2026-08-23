package com.yanhuozhang.offline.core

import java.text.Normalizer
import java.util.Locale

object CounterpartyNormalizer {
    private val maskedSuffix = Regex("""\([^)]*[*＊][^)]*\)""")
    private val separators = Regex("""[\p{P}\p{S}\s]+""")

    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) {
            return ""
        }
        val compatibleText = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return compatibleText
            .replace(maskedSuffix, "")
            .lowercase(Locale.ROOT)
            .replace(separators, "")
            .trim()
    }
}
