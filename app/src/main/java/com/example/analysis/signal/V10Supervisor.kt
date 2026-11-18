package com.example.analysis.signal

/** Independent rule-based role reviews of measured evidence. Not separate LLM requests. */
object V10Supervisor {
    enum class Verdict { PASS, CAUTION, BLOCK, UNAVAILABLE }
    data class Review(val role: String, val verdict: Verdict, val detail: String)
    data class Report(val reviews: List<Review>) {
        val blocksSimulationEntry: Boolean get() = reviews.any { it.verdict == Verdict.BLOCK }
    }

    fun review(evidence: TechnicalEvidenceResult?): Report {
        val categories = evidence?.components.orEmpty().associateBy { it.category }
        fun role(name: String, keys: List<EvidenceCategory>, critical: Boolean): Review {
            val states = keys.map { categories[it]?.state ?: EvidenceState.MISSING }
            val verdict = when {
                states.any { it == EvidenceState.CONTRADICTS } -> if (critical) Verdict.BLOCK else Verdict.CAUTION
                states.all { it == EvidenceState.SUPPORTS } -> Verdict.PASS
                states.all { it == EvidenceState.MISSING } -> Verdict.UNAVAILABLE
                else -> Verdict.CAUTION
            }
            return Review(name, verdict, keys.zip(states).joinToString(" · ") { (key,state) -> "${key.name}: ${state.name}" })
        }
        return Report(listOf(
            role("Market Analyst", listOf(EvidenceCategory.STRUCTURE, EvidenceCategory.MULTI_TIMEFRAME), true),
            role("SMC Specialist", listOf(EvidenceCategory.LIQUIDITY_SMC), false),
            role("Momentum Analyst", listOf(EvidenceCategory.MOMENTUM_VOLUME), false),
            role("Risk Manager", listOf(EvidenceCategory.RISK_REWARD, EvidenceCategory.SESSION_VOLATILITY), true)
        ))
    }

    /** Gross reward/risk at modeled fill, with adverse slippage, not the planned midpoint. */
    fun fillRiskReward(direction: String, quote: Double, stop: Double, target: Double, slippageBps: Double): Double? {
        if (direction !in listOf("LONG","SHORT") || !listOf(quote,stop,target,slippageBps).all { it.isFinite() } ||
            minOf(quote,stop,target) <= 0 || slippageBps !in 0.0..100.0) return null
        val buy = direction == "LONG"
        val fill = quote * (1 + (if (buy) 1 else -1) * slippageBps / 10000.0)
        val risk = if (buy) fill-stop else stop-fill
        val reward = if (buy) target-fill else fill-target
        if (risk <= 0 || reward <= 0) return null
        return (reward/risk).takeIf { it.isFinite() }
    }
}
