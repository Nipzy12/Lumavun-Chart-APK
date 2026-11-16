package com.example.ai.strategy

/**
 * LUMAVUN Chart — AI Strategy Hub (V3.0A)
 *
 * Strongly typed AI strategy definitions with eight selectable strategies.
 * Kept separate from [com.example.storage.preferences.TradingStyleProfile].
 */
enum class AiStrategy(
    val id: String,
    val displayName: String,
    val shortBadge: String,
    val shortDescription: String,
    val analysisInstructions: String
) {
    AI_HYBRID(
        id = "AI_HYBRID",
        displayName = "AI Hybrid",
        shortBadge = "Hybrid",
        shortDescription = "Comprehensive confluence across technical indicators, price action, SMC, ICT, and multi-timeframe structure.",
        analysisInstructions = """
            - Combine technical indicators (EMA, RSI, MACD, ATR), classical price action, Smart Money Concepts (SMC), Inner Circle Trader (ICT), and multi-timeframe evidence.
            - Require structural alignment across Higher Timeframe and Primary Timeframe.
            - Evaluate multiple analytical angles simultaneously to produce a balanced, robust market assessment.
        """.trimIndent()
    ),
    AI_SMART(
        id = "AI_SMART",
        displayName = "AI Smart",
        shortBadge = "Smart",
        shortDescription = "Adaptive analysis dynamically emphasizing trend, breakout, range, or pullback based on active market regime.",
        analysisInstructions = """
            - Dynamically adapt analytical emphasis to the current market regime (TRENDING, RANGING, BREAKOUT, PULLBACK, CHOPPY).
            - In TRENDING regimes, emphasize momentum, EMA slope, and continuation pullbacks.
            - In RANGING regimes, emphasize support/resistance boundaries and mean-reversion rejections.
            - In BREAKOUT regimes, verify expansion volume and structural retests.
            - In CHOPPY regimes, enforce WAIT for capital preservation.
        """.trimIndent()
    ),
    AI_SNR(
        id = "AI_SNR",
        displayName = "AI SNR",
        shortBadge = "SNR",
        shortDescription = "Automatic support/resistance rejection and breakout-retest analysis using the same levels as the chart indicator.",
        analysisInstructions = """
            - Use the actual supportLevels and resistanceLevels from the active LUMAVUN S/R indicator; do not invent lines, touches, or price history.
            - Identify the nearest confirmed support and resistance and distinguish rejection, breakout, retest, and neutral/range conditions.
            - Require confirmation from a CLOSED candle (body/wick relative to the selected level), plus ATR-based tolerance and market-data freshness.
            - For LONG, verify support rejection or resistance breakout/retest; for SHORT, verify resistance rejection or support breakdown/retest.
            - Place invalidation (SL) outside the relevant S/R level and ensure TP does not exceed the next measured opposing level without evidence.
            - If levels are missing, the price is far from a level, the market feed is stale, or risk/reward is poor, output WAIT and explain why.
            - Use AI conclusions as analysis only; never claim automatic real-order execution or guaranteed trade outcomes.
        """.trimIndent()
    ),
    AI_SMC(
        id = "AI_SMC",
        displayName = "AI SMC",
        shortBadge = "SMC",
        shortDescription = "Institutional Smart Money Concepts: market structure (BOS/CHoCH), order blocks, and internal liquidity pools.",
        analysisInstructions = """
            - Prioritize Smart Money Concepts (SMC) methodology.
            - Analyze structural shifts: Break of Structure (BOS) and Change of Character (CHoCH).
            - Identify and mitigate verified institutional Order Blocks (OBs) with valid zone boundaries.
            - Anchor entry zones strictly to unmitigated order blocks and clear structural footprints.
            - Reject setups if no genuine institutional structure or order block footprint is present.
        """.trimIndent()
    ),
    AI_ICT(
        id = "AI_ICT",
        displayName = "AI ICT",
        shortBadge = "ICT",
        shortDescription = "Inner Circle Trader methodologies: liquidity sweeps, displacement, Fair Value Gaps (FVG), and premium/discount valuation.",
        analysisInstructions = """
            - Prioritize Inner Circle Trader (ICT) concepts and delivery algorithms.
            - Identify liquidity sweeps of key previous highs (buy-side) and lows (sell-side).
            - Require clean displacement following liquidity runs and locate Fair Value Gaps (FVG) or liquidity voids.
            - Assess whether current market price resides in Premium (for shorts) or Discount (for longs) relative to the dealing range.
            - Reject setups if liquidity has not been purged or displacement is absent.
        """.trimIndent()
    ),
    AI_SNIPER(
        id = "AI_SNIPER",
        displayName = "AI Sniper",
        shortBadge = "Sniper",
        shortDescription = "High-selectivity precision entries with strict risk/reward constraints and strong multi-factor confirmation.",
        analysisInstructions = """
            - Enforce ultra-high selectivity and extreme discipline ("one shot, one kill").
            - Require strict confluence: Higher Timeframe trend + Primary structure + tight entry zone with minimum Risk/Reward >= 2.0.
            - Require high conviction (confidence >= 70) and zero indicator conflict.
            - Default aggressively to WAIT whenever any ambiguity, wide stop loss, or conflicting signal exists.
        """.trimIndent()
    ),
    AI_SCALPING(
        id = "AI_SCALPING",
        displayName = "AI Scalping",
        shortBadge = "Scalping",
        shortDescription = "Short-horizon micro-momentum with fast invalidation, tight stops, and spread/fee compensation.",
        analysisInstructions = """
            - Prioritize short-horizon micro-momentum and immediate price action response.
            - Validate RSI/MACD impulse velocity and immediate EMA20 holding.
            - Calculate expected move to TP1 and ensure it comfortably exceeds spread and round-trip trading fees.
            - Enforce rapid structural invalidation if price fails to expand immediately in the intended direction.
        """.trimIndent()
    ),
    AI_PRICE_ACTION(
        id = "AI_PRICE_ACTION",
        displayName = "AI Price Action",
        shortBadge = "Price Action",
        shortDescription = "Pure candlestick behavior, rejection wicks, breakout/retest dynamics, and swing structure.",
        analysisInstructions = """
            - Focus exclusively on pure naked candlestick price action and structural geometry.
            - Evaluate candle formations: pin bars, rejection wicks, engulfing bodies, and exhaustion prints.
            - Monitor horizontal support and resistance breakouts, retests, and swing high / swing low sequences.
            - Minimize reliance on lagging indicators in favor of raw price behavior and rejection levels.
        """.trimIndent()
    ),
    AI_SESSION_TRADER(
        id = "AI_SESSION_TRADER",
        displayName = "AI Session Trader",
        shortBadge = "Session",
        shortDescription = "Session-aware analysis targeting Asia/London/NY session highs/lows, opens, and overlaps.",
        analysisInstructions = """
            - Focus on trading session dynamics: Asian range, London open expansion, New York reversal/continuation, and session overlaps.
            - Monitor sweeps of prior session highs and lows for liquidity raid setups.
            - Utilize session context only when reliable timestamps and session data are available in the technical feed.
            - If session data is unavailable or ambiguous, fall back gracefully to standard structural price analysis with a clear disclosure.
        """.trimIndent()
    );

    companion object {
        val DEFAULT: AiStrategy = AI_HYBRID

        fun fromId(id: String?): AiStrategy {
            if (id.isNullOrBlank()) return DEFAULT
            val clean = id.trim()
            return entries.firstOrNull { it.id.equals(clean, ignoreCase = true) }
                ?: entries.firstOrNull { it.displayName.equals(clean, ignoreCase = true) }
                ?: entries.firstOrNull { it.shortBadge.equals(clean, ignoreCase = true) }
                ?: DEFAULT
        }
    }
}
