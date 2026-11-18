package com.example.analysis.signal

import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.analysis.technical.MtfConfirmationStatus
import com.example.analysis.technical.MarketRegime
import com.example.analysis.smc.SweepDetectionState
import java.util.Locale

/** Diagnostic only: no AI-authored claims, no probability, no order permission. */
object SnapshotEvidenceAdapter {
    fun evaluate(snapshot: MarketTechnicalSnapshot, decision: String, worstRr: Double, minRr: Double): TechnicalEvidenceResult {
        val buy = decision == "LONG"
        if (!buy && decision != "SHORT") return TechnicalEvidenceScore.evaluate(emptyMap(), null, minRr)
        val target = if (buy) "BULLISH" else "BEARISH"
        val opposing = if (buy) "BEARISH" else "BULLISH"
        val states = mutableMapOf<EvidenceCategory, EvidenceState>()
        fun bias(raw: String?): EvidenceState = when (raw?.uppercase(Locale.US)) {
            target -> EvidenceState.SUPPORTS
            opposing -> EvidenceState.CONTRADICTS
            null -> EvidenceState.MISSING
            else -> EvidenceState.PARTIAL
        }
        states[EvidenceCategory.STRUCTURE] = if (snapshot.marketStructure.confirmedSwings.isEmpty())
            EvidenceState.MISSING else bias(snapshot.marketStructure.state.name)
        val mtf = snapshot.mtfConfirmation
        val higher = snapshot.higherContext?.marketCondition
        val lower = snapshot.lowerContext?.marketCondition
        states[EvidenceCategory.MULTI_TIMEFRAME] = when {
            mtf.status == MtfConfirmationStatus.CONFLICT || higher == opposing || (mtf.hasLowerTfData && !mtf.isLowerTfStale && lower == opposing) -> EvidenceState.CONTRADICTS
            !mtf.hasHigherTfData || mtf.isHigherTfStale || higher == null -> EvidenceState.MISSING
            mtf.status == MtfConfirmationStatus.ALIGNED && higher == target && mtf.hasLowerTfData && !mtf.isLowerTfStale && lower == target -> EvidenceState.SUPPORTS
            higher == target -> EvidenceState.PARTIAL
            else -> EvidenceState.MISSING
        }
        val sweep = snapshot.smcAnalysis?.latestActiveSweep
        val ob = snapshot.marketStructure.activeOrderBlockCandidates.any { !it.isMitigated && it.isBullish == buy }
        states[EvidenceCategory.LIQUIDITY_SMC] = when {
            sweep != null && sweep.state == SweepDetectionState.CONFIRMED_SWEEP && !sweep.isInvalidated && sweep.isBullishSweep != buy -> EvidenceState.CONTRADICTS
            sweep != null && sweep.state == SweepDetectionState.CONFIRMED_SWEEP && !sweep.isInvalidated && !sweep.isIntrabarObservation && sweep.isBullishSweep == buy -> if (ob) EvidenceState.SUPPORTS else EvidenceState.PARTIAL
            ob -> EvidenceState.PARTIAL
            else -> EvidenceState.MISSING
        }
        val primary = snapshot.primaryContext
        val rsi = primary?.rsi14
        val ema = primary?.emaAlignment
        val vol = primary?.volumeCondition
        states[EvidenceCategory.MOMENTUM_VOLUME] = when {
            rsi == null || !rsi.isFinite() || rsi !in 0.0..100.0 || ema == null -> EvidenceState.MISSING
            ema == opposing || (buy && rsi < 45.0) || (!buy && rsi > 55.0) -> EvidenceState.CONTRADICTS
            ema == target && (if (buy) rsi >= 55.0 else rsi <= 45.0) && vol in listOf("NORMAL","INCREASING") && !snapshot.isVolumeWeak -> EvidenceState.SUPPORTS
            else -> EvidenceState.PARTIAL
        }
        val session = snapshot.smcAnalysis?.sessionSnapshot
        val spread = snapshot.smcAnalysis?.spreadVolatilityValidation
        states[EvidenceCategory.SESSION_VOLATILITY] = when {
            snapshot.isVolatilityAbnormal || session?.isInstrumentTradableNow == false || spread?.isSpreadExcessive == true -> EvidenceState.CONTRADICTS
            session?.isInstrumentTradableNow == true && spread?.isVolatilityAcceptable == true && spread.isBidAskAvailable && spread.isSpreadCheckPassed -> EvidenceState.SUPPORTS
            session?.isInstrumentTradableNow == true && spread?.isVolatilityAcceptable == true -> EvidenceState.PARTIAL
            else -> EvidenceState.MISSING
        }
        return TechnicalEvidenceScore.evaluate(states, worstRr, minRr)
    }

    fun strategyFocus(regime: MarketRegime): String = when (regime) {
        MarketRegime.TRENDING -> "Trend continuation / pullback"
        MarketRegime.PULLBACK -> "Wait for trend-aligned retest"
        MarketRegime.BREAKOUT -> "Confirmed breakout + retest"
        MarketRegime.RANGING -> "Measured support / resistance rejection"
        MarketRegime.LOW_VOLATILITY -> "Wait for expansion confirmation"
        MarketRegime.HIGH_VOLATILITY -> "Reduce exposure / wait for normalization"
        MarketRegime.CHOPPY, MarketRegime.INSUFFICIENT_DATA -> "WAIT - insufficient structure"
    }
}
