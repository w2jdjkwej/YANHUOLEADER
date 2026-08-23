package com.yanhuozhang.offline.data

import com.yanhuozhang.offline.core.CounterpartyNormalizer
import com.yanhuozhang.offline.core.ParsedObservation
import com.yanhuozhang.offline.core.PaymentChannel

object PersonalRuleMatcher {
    fun findMatch(
        rules: List<PersonalRuleEntity>,
        observation: ParsedObservation,
        paymentChannel: PaymentChannel,
    ): PersonalRuleEntity? {
        val normalizedCounterparty = CounterpartyNormalizer.normalize(observation.counterparty)
        return rules.firstOrNull { rule ->
            val normalizedRuleText = CounterpartyNormalizer.normalize(rule.matchText)
            val counterpartyMatches = normalizedCounterparty.isNotEmpty() &&
                normalizedCounterparty == normalizedRuleText
            val legacyRawTextMatches = observation.rawText.contains(rule.matchText, ignoreCase = true)
            (counterpartyMatches || legacyRawTextMatches) &&
                (rule.direction == null || rule.direction == observation.direction) &&
                (rule.paymentChannel == null || rule.paymentChannel == paymentChannel)
        }
    }
}
