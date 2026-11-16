package com.example.analysis.supportResistance

import com.example.market.candle.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnrAutoAnalysisEngineTest {
    private fun candle(
        n: Int,
        open: Double = 100.5,
        high: Double = 100.8,
        low: Double = 100.2,
        close: Double = 100.6,
        isClosed: Boolean = true
    ) = Candle(
        openTime = 1_000L + n * 60_000L,
        open = open, high = high, low = low, close = close,
        volume = 10.0, closeTime = 60_999L + n * 60_000L,
        isClosed = isClosed
    )

    @Test fun supportRejectionOnConfirmedCandleTriggersReview() {
        val history = (0 until 19).map { candle(it) }
        val signal = candle(19, open = 100.2, high = 101.1, low = 99.95, close = 100.8)
        val result = SnrAutoAnalysisEngine.evaluate(history + signal, listOf(100.0), listOf(110.0))
        assertTrue(result.shouldAnalyze)
        assertEquals("SUPPORT_REJECTION", result.type)
        assertEquals(100.0, result.level!!, 0.001)
    }

    @Test fun resistanceRejectionOnConfirmedCandleTriggersReview() {
        val history = (0 until 19).map { candle(it) }
        val signal = candle(19, open = 110.0, high = 110.04, low = 108.9, close = 109.1)
        val result = SnrAutoAnalysisEngine.evaluate(history + signal, listOf(95.0), listOf(110.0))
        assertTrue(result.shouldAnalyze)
        assertEquals("RESISTANCE_REJECTION", result.type)
    }

    @Test fun nonConfirmedCandleMustNotTriggerReview() {
        val history = (0 until 20).map { candle(it) }
        val unclosed = candle(20, open = 100.1, high = 101.1, low = 99.98, close = 100.9, isClosed = false)
        val result = SnrAutoAnalysisEngine.evaluate(history + unclosed, listOf(100.0), emptyList())
        assertFalse(result.shouldAnalyze)
    }

    @Test fun missingLevelsMustWait() {
        val history = (0 until 20).map { candle(it) }
        assertFalse(SnrAutoAnalysisEngine.evaluate(history, emptyList(), emptyList()).shouldAnalyze)
    }

    @Test fun insufficientHistoryMustWait() {
        val result = SnrAutoAnalysisEngine.evaluate(
            listOf(candle(1, low = 99.9)),
            listOf(100.0),
            emptyList()
        )
        assertFalse(result.shouldAnalyze)
    }
}
