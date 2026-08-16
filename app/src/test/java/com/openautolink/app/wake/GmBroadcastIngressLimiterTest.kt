package com.openautolink.app.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmBroadcastIngressLimiterTest {

    @Test
    fun `alternating raw values cannot bypass per action window limit`() {
        val limiter = GmBroadcastIngressLimiter(maxPerWindow = 4, hardCapPerEpoch = 20)
        val outcomes = (0 until 6).map { index ->
            limiter.evaluate("SYSTEM_STATE_CHANGED", index % 2, elapsedMs = index.toLong(), epoch = 1L)
        }

        assertEquals(4, outcomes.count { it.allowed })
        assertEquals(2, outcomes.count { it.outcome == GmIngressOutcome.WINDOW_LIMITED })
        assertEquals(1, outcomes.count { it.shouldLogSuppression })
    }

    @Test
    fun `repeated duplicate is denied before accepted callback`() {
        val limiter = GmBroadcastIngressLimiter(maxPerWindow = 4, hardCapPerEpoch = 20)
        var expensiveCallbacks = 0

        val first = limiter.handle("POWER_MODE_CHANGED", 7, 100L, epoch = 1L) {
            expensiveCallbacks++
        }
        val duplicate = limiter.handle("POWER_MODE_CHANGED", 7, 101L, epoch = 1L) {
            expensiveCallbacks++
        }

        assertTrue(first.allowed)
        assertEquals(GmIngressOutcome.DUPLICATE, duplicate.outcome)
        assertFalse(duplicate.allowed)
        assertEquals(1, expensiveCallbacks)
    }

    @Test
    fun `ignition power epoch resets dedupe and limits`() {
        val limiter = GmBroadcastIngressLimiter(maxPerWindow = 1, hardCapPerEpoch = 1)

        assertTrue(limiter.evaluate("SYSTEM_STATE_CHANGED", 4, 100L, epoch = 1L).allowed)
        assertFalse(limiter.evaluate("SYSTEM_STATE_CHANGED", 5, 101L, epoch = 1L).allowed)
        assertTrue(limiter.evaluate("SYSTEM_STATE_CHANGED", 4, 102L, epoch = 2L).allowed)
    }

    @Test
    fun `POWEROFF callbacks are deduped and window limited before expensive work`() {
        val limiter = GmBroadcastIngressLimiter(maxPerWindow = 2, hardCapPerEpoch = 8)
        var expensiveCallbacks = 0
        val states = listOf(
            Triple(true, false, null),
            Triple(true, false, null),
            Triple(false, false, null),
            Triple(null, true, false),
            Triple(true, true, true),
        )

        val results = states.mapIndexed { index, (view, mute, fpi) ->
            limiter.handle(
                action = "POWEROFF_VIEW",
                rawValue = GmIngressDedupeKey.poweroffView(view, mute, fpi),
                elapsedMs = 100L + index,
                epoch = 1L,
            ) { expensiveCallbacks++ }
        }

        assertEquals(2, expensiveCallbacks)
        assertEquals(GmIngressOutcome.DUPLICATE, results[1].outcome)
        assertEquals(2, results.count { it.outcome == GmIngressOutcome.WINDOW_LIMITED })
        limiter.handle(
            "POWEROFF_VIEW",
            GmIngressDedupeKey.poweroffView(true, false, null),
            200L,
            epoch = 2L,
        ) { expensiveCallbacks++ }
        assertEquals(3, expensiveCallbacks)
    }

    @Test
    fun `HOME callback floods are gated before expensive work and reset by epoch`() {
        val limiter = GmBroadcastIngressLimiter(maxPerWindow = 2, hardCapPerEpoch = 8)
        var expensiveCallbacks = 0

        repeat(20) { index ->
            limiter.handle(
                action = "HOME_STARTED",
                rawValue = GmIngressDedupeKey.ACTION_ONLY,
                elapsedMs = 300L + index,
                epoch = 5L,
            ) { expensiveCallbacks++ }
        }
        assertEquals(1, expensiveCallbacks)

        limiter.handle(
            action = "HOME_STARTED",
            rawValue = GmIngressDedupeKey.ACTION_ONLY,
            elapsedMs = 400L,
            epoch = 6L,
        ) { expensiveCallbacks++ }
        assertEquals(2, expensiveCallbacks)
    }

    @Test
    fun `hard cap bounds accepted events even outside rate window`() {
        val limiter = GmBroadcastIngressLimiter(
            windowMs = 10L,
            maxPerWindow = 4,
            hardCapPerEpoch = 3,
        )

        val outcomes = (0 until 5).map { index ->
            limiter.evaluate(
                action = "SYSTEM_STATE_CHANGED",
                rawValue = index,
                elapsedMs = index * 20L,
                epoch = 9L,
            )
        }

        assertEquals(3, outcomes.count { it.allowed })
        assertEquals(2, outcomes.count { it.outcome == GmIngressOutcome.HARD_CAPPED })
        assertEquals(1, outcomes.count { it.shouldLogSuppression })
        assertEquals(3, limiter.acceptedCount("SYSTEM_STATE_CHANGED"))
    }
}
