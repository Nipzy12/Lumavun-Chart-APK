package com.example.analysis.signal

import com.example.ai.analyst.AiAnalysisRequestContext
import com.example.ai.parser.AiAnalysisResult
import com.example.ai.parser.AiResponseParser
import com.example.analysis.marketStructure.MarketStructureAnalysis
import com.example.analysis.marketStructure.StructureState
import com.example.analysis.technical.MarketRegime
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.analysis.technical.MtfConfirmation
import com.example.analysis.technical.MtfConfirmationStatus
import com.example.market.candle.Candle
import com.example.market.candle.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfirmedLimitRecommendationEngineTest {
    private val now = 1_800_000_000_000L
    private val period = Timeframe.M5.durationMs

    private fun candles(isBuy: Boolean, closeConfirmed: Boolean = true): List<Candle> {
        return (0 until 30).map { idx ->
            val isLast = idx == 29
            val open = if (isLast) (if (isBuy) 101.5 else 108.6) else
                (if (isBuy) 101.5 else 108.5)
            val low = if (isLast) (if (isBuy) 100.02 else 108.1) else
                (if (isBuy) 101.2 else 108.1)
            val high = if (isLast) (if (isBuy) 102.5 else 110.04) else
                (if (isBuy) 102.3 else 108.8)
            val close = if (isLast) (if (isBuy) 102.0 else 108.2) else
                (if (isBuy) 101.8 else 108.3)
            val openTime = now - (30 - idx) * period - 10_000L
            Candle(
                openTime = openTime,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = 100.0,
                closeTime = openTime + period - 1L,
                isClosed = !isLast || closeConfirmed
            )
        }
    }

    private fun snapshot(
        isBuy: Boolean = true,
        candleConfirmed: Boolean = true,
        supports: List<Double> = listOf(100.0, 95.0, 102.0),
        resistances: List<Double> = listOf(107.0, 110.0),
        regime: MarketRegime = MarketRegime.RANGING,
        mtf: MtfConfirmationStatus = MtfConfirmationStatus.ALIGNED
    ): MarketTechnicalSnapshot {
        val c = candles(isBuy, candleConfirmed)
        val price = c.last().close
        return MarketTechnicalSnapshot(
            symbol = "XAUUSD",
            timeframe = Timeframe.M5,
            currentPrice = price,
            latestCandle = c.last(),
            recentCandles = c,
            ema20 = price,
            ema50 = price,
            ema200 = price,
            rsi14 = 50.0,
            macd = null,
            atr14 = 1.0,
            currentVolume = 100.0,
            volumeSma20 = 100.0,
            supportLevels = if (isBuy) supports else listOf(102.0),
            resistanceLevels = if (isBuy) resistances else listOf(110.0, 116.0),
            marketStructure = MarketStructureAnalysis(
                state = if (isBuy) StructureState.BULLISH else StructureState.BEARISH,
                schemaCode = if (isBuy) "HH_HL" else "LH_LL",
                swingSummary = "Confirmed S/R reaction",
                biasLabel = if (isBuy) "BULLISH" else "BEARISH",
                lastHighTag = null,
                lastLowTag = null,
                lastSwingHighPrice = price + 8.0,
                lastSwingLowPrice = price - 8.0,
                recentSwings = emptyList()
            ),
            hasEmaConflict = false,
            hasRsiConflict = false,
            hasMacdConflict = false,
            isVolumeWeak = false,
            isNearMajorResistance = false,
            isNearMajorSupport = false,
            marketRegime = regime,
            mtfConfirmation = MtfConfirmation(
                status = mtf,
                summary = "Test MTF",
                hasHigherTfData = true,
                hasLowerTfData = true
            )
        )
    }

    private fun waitResult(): AiAnalysisResult =
        requireNotNull(AiResponseParser.parse(
            """{"decision":"WAIT","confidence":45,"trend":"SIDEWAYS","momentum":"NEUTRAL","marketStructure":"SIDEWAYS","setupQuality":"WAIT","summary":"Existing AI waited for confirmation"}"""
        ))

    @Test fun buyLimitIsPendingAndBelowPriceOnConfirmedSupportRejection() {
        val proposal = ConfirmedLimitRecommendationEngine.propose(
            waitResult(), snapshot(isBuy = true), AiAnalysisRequestContext(), now
        )
        assertNotNull(proposal)
        assertEquals("LONG", proposal!!.decision)
        assertEquals("BUY LIMIT", proposal.structuredOrderActionLabel)
        assertTrue(proposal.isPendingOrderRecommendation)
        assertFalse(proposal.isActionableMarketRecommendation)
        assertTrue(proposal.entryZone.high < 102.0)
        assertTrue(proposal.stopLoss < proposal.entryZone.low)
        assertTrue(proposal.takeProfit.first() > proposal.entryZone.high)
        assertTrue(proposal.reasoning.any { it.contains("Closed candle") })
        assertEquals("CONFIRMED_PENDING_LIMIT", proposal.setupDecisionState)
        val cached = AiResponseParser.parse(proposal.rawJson)
        assertNotNull("Pending LIMIT must survive cached analysis reload", cached)
        assertEquals("BUY LIMIT", cached!!.structuredOrderActionLabel)
    }

    @Test fun sellLimitIsPendingAndAbovePriceOnConfirmedResistanceRejection() {
        val proposal = ConfirmedLimitRecommendationEngine.propose(
            waitResult(), snapshot(isBuy = false), AiAnalysisRequestContext(), now
        )
        assertNotNull(proposal)
        assertEquals("SHORT", proposal!!.decision)
        assertEquals("SELL LIMIT", proposal.structuredOrderActionLabel)
        assertTrue(proposal.entryZone.low > 108.2)
        assertTrue(proposal.stopLoss > proposal.entryZone.high)
        assertTrue(proposal.takeProfit.first() < proposal.entryZone.low)
    }

    @Test fun noClosedRejectionMeansNoForcedSignal() {
        assertNull(ConfirmedLimitRecommendationEngine.propose(
            waitResult(), snapshot(isBuy = true, candleConfirmed = false),
            AiAnalysisRequestContext(), now
        ))
    }

    @Test fun invalidFeedIsNeverPromoted() {
        assertNull(ConfirmedLimitRecommendationEngine.propose(
            waitResult(), snapshot(), AiAnalysisRequestContext(isDataValidForEntry = false), now
        ))
    }

    @Test fun conflictingTimeframesOrChoppyRegimeRemainWait() {
        assertNull(ConfirmedLimitRecommendationEngine.propose(
            waitResult(), snapshot(mtf = MtfConfirmationStatus.CONFLICT),
            AiAnalysisRequestContext(), now
        ))
        assertNull(ConfirmedLimitRecommendationEngine.propose(
            waitResult(), snapshot(regime = MarketRegime.CHOPPY),
            AiAnalysisRequestContext(), now
        ))
    }

    @Test fun staleConfirmationRemainsWait() {
        assertNull(ConfirmedLimitRecommendationEngine.propose(
            waitResult(), snapshot(), AiAnalysisRequestContext(), now + period * 4
        ))
    }

    @Test fun missingOpposingTargetOrPoorRewardRemainWait() {
        assertNull(ConfirmedLimitRecommendationEngine.propose(
            waitResult(), snapshot(resistances = emptyList()),
            AiAnalysisRequestContext(), now
        ))
        assertNull(ConfirmedLimitRecommendationEngine.propose(
            waitResult(), snapshot(resistances = listOf(102.4)).copy(atr14 = 2.5),
            AiAnalysisRequestContext(), now
        ))
    }

    @Test fun nonWaitOriginalIsNotRewritten() {
        val nonWait = waitResult().copy(decision = "LONG")
        assertNull(ConfirmedLimitRecommendationEngine.propose(
            nonWait, snapshot(), AiAnalysisRequestContext(), now
        ))
    }
}
