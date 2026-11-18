package com.example.market.exness

import org.junit.Assert.*
import org.junit.Test

class ExnessQuoteGuardTest {
    @Test fun rejectsNonFiniteCrossedAndHalfQuotes() {
        assertTrue(ExnessQuoteGuard.valid(100.0,99.0,101.0))
        assertTrue(ExnessQuoteGuard.valid(100.0,0.0,0.0))
        assertFalse(ExnessQuoteGuard.valid(Double.NaN,99.0,101.0))
        assertFalse(ExnessQuoteGuard.valid(100.0,101.0,99.0))
        assertFalse(ExnessQuoteGuard.valid(100.0,0.0,101.0))
    }
    @Test fun pollingOnlySleepsForFreshSocketQuotes() {
        assertTrue(ExnessQuoteGuard.socketFresh(1000L,11000L))
        assertFalse(ExnessQuoteGuard.socketFresh(1000L,11001L))
        assertFalse(ExnessQuoteGuard.socketFresh(0L,100L))
        assertFalse(ExnessQuoteGuard.socketFresh(1000L,999L))
    }
}
