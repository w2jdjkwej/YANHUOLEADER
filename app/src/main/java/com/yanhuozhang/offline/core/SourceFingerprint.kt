package com.yanhuozhang.offline.core

import java.security.MessageDigest

object SourceFingerprint {
    fun create(raw: RawObservation): String {
        val normalizedTitle = normalize(raw.title.orEmpty())
        val normalizedContent = normalize(raw.content)
        val input = listOf(
            raw.source.name,
            raw.sourceEventId,
            raw.observedAtMillis.toString(),
            normalizedTitle,
            normalizedContent,
        ).joinToString("|")

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun normalize(text: String): String {
        return text.lowercase().filterNot { character -> character.isWhitespace() }
    }
}
