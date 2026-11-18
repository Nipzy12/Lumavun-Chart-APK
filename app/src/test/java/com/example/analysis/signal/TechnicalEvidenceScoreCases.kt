package com.example.analysis.signal

object TechnicalEvidenceScoreCases {
    fun verify() {
        val supports = EvidenceCategory.values().associateWith { EvidenceState.SUPPORTS }
        val full = TechnicalEvidenceScore.evaluate(supports, 2.0, 1.35)
        check(full.score == 100 && full.coverage == 100) { "Full evidence must score 100" }
        val missing = TechnicalEvidenceScore.evaluate(emptyMap(), null, 1.35)
        check(missing.score == 0 && missing.coverage == 0)
        val partial = TechnicalEvidenceScore.evaluate(mapOf(EvidenceCategory.STRUCTURE to EvidenceState.PARTIAL, EvidenceCategory.MULTI_TIMEFRAME to EvidenceState.CONTRADICTS), 1.5, 1.35)
        check(partial.score == 19 && partial.coverage == 60)
        val below = TechnicalEvidenceScore.evaluate(supports, 1.349, 1.35)
        check(below.score == 85 && below.components.single { it.category == EvidenceCategory.RISK_REWARD }.state == EvidenceState.CONTRADICTS)
        val unknown = TechnicalEvidenceScore.evaluate(supports, Double.NaN, 1.35)
        check(unknown.score == 85 && unknown.coverage == 85)
        val badMinimum = TechnicalEvidenceScore.evaluate(supports, 2.0, Double.POSITIVE_INFINITY)
        check(badMinimum.score == 85 && badMinimum.coverage == 85)
        val sniper = TechnicalEvidenceScore.evaluate(supports, 2.0, 2.5)
        check(sniper.score == 85)
        check(full == TechnicalEvidenceScore.evaluate(supports, 2.0, 1.35))
    }
}

fun main() {
    TechnicalEvidenceScoreCases.verify()
    println("8 evidence checks passed")
}
