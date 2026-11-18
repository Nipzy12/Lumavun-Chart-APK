package com.example.analysis.signal

enum class EvidenceState { SUPPORTS, PARTIAL, CONTRADICTS, MISSING }

enum class EvidenceCategory(val weight: Int) {
    STRUCTURE(25), MULTI_TIMEFRAME(20), LIQUIDITY_SMC(20), MOMENTUM_VOLUME(15), RISK_REWARD(15), SESSION_VOLATILITY(5)
}

data class EvidenceComponent(val category: EvidenceCategory, val state: EvidenceState, val points: Int)

data class TechnicalEvidenceResult(val components: List<EvidenceComponent>) {
    val score: Int get() = components.sumOf { it.points }
    val coverage: Int get() = components.filter { it.state != EvidenceState.MISSING }.sumOf { it.category.weight }
}

object TechnicalEvidenceScore {
    // Fixed denominator: missing evidence is never treated as positive or renormalized.
    // This is an analytical alignment score, not a win probability or entry authorization.
    fun evaluate(states: Map<EvidenceCategory, EvidenceState>, worstCaseRr: Double?, minimumRr: Double): TechnicalEvidenceResult {
        val riskState = when {
            worstCaseRr == null || !worstCaseRr.isFinite() || worstCaseRr <= 0.0 || !minimumRr.isFinite() || minimumRr <= 0.0 -> EvidenceState.MISSING
            worstCaseRr < minimumRr -> EvidenceState.CONTRADICTS
            worstCaseRr >= maxOf(2.0, minimumRr) -> EvidenceState.SUPPORTS
            else -> EvidenceState.PARTIAL
        }
        return TechnicalEvidenceResult(EvidenceCategory.values().map { category ->
            val state = if (category == EvidenceCategory.RISK_REWARD) riskState else states[category] ?: EvidenceState.MISSING
            val points = when (state) {
                EvidenceState.SUPPORTS -> category.weight
                EvidenceState.PARTIAL -> category.weight / 2
                EvidenceState.CONTRADICTS, EvidenceState.MISSING -> 0
            }
            EvidenceComponent(category, state, points)
        })
    }
}
