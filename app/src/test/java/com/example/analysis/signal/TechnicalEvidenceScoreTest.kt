package com.example.analysis.signal

import org.junit.Test

class TechnicalEvidenceScoreTest {
    @Test
    fun `deterministic score respects evidence availability and exact risk threshold`() {
        TechnicalEvidenceScoreCases.verify()
    }
}
