package com.example.market.exness

object ExnessQuoteGuard {
    fun valid(last: Double, bid: Double, ask: Double): Boolean =
        listOf(last,bid,ask).all { it.isFinite() } && last > 0.0 && bid >= 0.0 && ask >= 0.0 &&
            ((bid == 0.0 && ask == 0.0) || (bid > 0.0 && ask >= bid))

    fun socketFresh(last: Long, now: Long): Boolean = last > 0L && now >= last && now-last <= 10_000L
}
