package com.openautolink.app.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptSummarySchedulerTest {

    @Test
    fun `process start without wake candidate still times out with one summary`() {
        val harness = Harness()
        val summary = summary(attemptId = 1L, WakeSignal.PROCESS_START)

        harness.scheduler.observe(current = summary, previous = null)
        harness.fire(1L)

        assertEquals(listOf(1L to AttemptSummaryOutcome.TIMEOUT), harness.emitted)
    }

    @Test
    fun `old attempt timer resolves old summary after rollover`() {
        val harness = Harness()
        val old = summary(attemptId = 10L, WakeSignal.PROCESS_START)
        val current = summary(attemptId = 11L, WakeSignal.IGNITION_ON)

        harness.scheduler.observe(current = old, previous = null)
        harness.scheduler.observe(current = current, previous = old)
        harness.fire(10L)

        assertEquals(listOf(10L to AttemptSummaryOutcome.TIMEOUT), harness.emitted)
        assertEquals(setOf(10L, 11L), harness.scheduler.retainedAttemptIds)
        assertEquals(1, harness.scheduler.activeTimeoutCount)
    }

    @Test
    fun `ready racing timeout emits exactly once and cancels that attempt timer`() {
        val harness = Harness()
        val summary = summary(attemptId = 20L, WakeSignal.IGNITION_ON)

        harness.scheduler.observe(current = summary, previous = null)
        val staleTimeout = harness.callback(20L)
        assertTrue(harness.scheduler.ready(summary))
        staleTimeout()

        assertEquals(listOf(20L to AttemptSummaryOutcome.READY), harness.emitted)
        assertTrue(harness.cancelled.getValue(20L))
        assertEquals(0, harness.scheduler.activeTimeoutCount)
    }

    @Test
    fun `timeout emits the newest snapshot observed for its exact attempt`() {
        val harness = Harness()
        val initial = summary(attemptId = 30L, WakeSignal.PROCESS_START)
        val updated = initial.copy(surfaceReadyAtMs = 500L)

        harness.scheduler.observe(current = initial, previous = null)
        harness.scheduler.observe(current = updated, previous = null)
        harness.fire(30L)

        assertEquals(500L, harness.emittedSummaries.single().surfaceReadyAtMs)
        assertEquals(listOf(30L to AttemptSummaryOutcome.TIMEOUT), harness.emitted)
    }

    @Test
    fun `real reducer retains first readiness source and scheduler emits once per attempt`() {
        var nextId = 40L
        val reducer = WakeAttemptReducer { nextId++ }
        val harness = Harness()

        fun record(event: WakeEvent): WakeSummary {
            val current = reducer.record(event)
            harness.scheduler.observe(current, reducer.previousSummary)
            if (current.sessionReadyAtMs != null && current.surfaceReadyAtMs != null) {
                harness.scheduler.ready(current)
            }
            return current
        }

        record(
            WakeEvent(
                WakeSignal.SESSION_READY,
                elapsedMs = 100L,
                detail = "ready=true,source=callback-installed",
            )
        )
        val staleFirstTimeout = harness.callback(40L)
        record(WakeEvent(WakeSignal.SURFACE_READY, elapsedMs = 110L))
        record(
            WakeEvent(
                WakeSignal.SESSION_READY,
                elapsedMs = 120L,
                detail = "ready=true,source=native-session-started",
            )
        )
        staleFirstTimeout()

        assertEquals(listOf(40L to AttemptSummaryOutcome.READY), harness.emitted)
        assertEquals("callback-installed", harness.emittedSummaries.single().sessionReadySource)
        assertTrue(harness.cancelled.getValue(40L))

        record(WakeEvent(WakeSignal.IGNITION_OFF, elapsedMs = 200L))
        record(
            WakeEvent(
                WakeSignal.SESSION_READY,
                elapsedMs = 300L,
                detail = "ready=true,source=native-session-started",
            )
        )
        val staleReconnectTimeout = harness.callback(41L)
        record(WakeEvent(WakeSignal.SURFACE_READY, elapsedMs = 310L))
        staleReconnectTimeout()

        assertEquals(
            listOf(
                40L to AttemptSummaryOutcome.READY,
                41L to AttemptSummaryOutcome.READY,
            ),
            harness.emitted,
        )
        assertEquals(
            listOf("callback-installed", "native-session-started"),
            harness.emittedSummaries.map { it.sessionReadySource },
        )
        assertEquals(0, harness.scheduler.activeTimeoutCount)
    }

    private class Harness {
        val callbacks = mutableMapOf<Long, () -> Unit>()
        val cancelled = mutableMapOf<Long, Boolean>()
        val emitted = mutableListOf<Pair<Long, AttemptSummaryOutcome>>()
        val emittedSummaries = mutableListOf<WakeSummary>()
        val scheduler = AttemptSummaryScheduler(
            timeoutMs = 15_000L,
            schedule = { attemptId, delayMs, callback ->
                assertEquals(15_000L, delayMs)
                callbacks[attemptId] = callback
                cancelled[attemptId] = false
                AttemptSummaryTimeout { cancelled[attemptId] = true }
            },
            emit = { summary, outcome ->
                emittedSummaries += summary
                emitted += summary.attemptId to outcome
            },
        )

        fun callback(attemptId: Long): () -> Unit = callbacks.getValue(attemptId)

        fun fire(attemptId: Long) = callback(attemptId).invoke()
    }

    private fun summary(attemptId: Long, trigger: WakeSignal): WakeSummary = WakeSummary(
        attemptId = attemptId,
        trigger = trigger,
        gmState = null,
        btReadyAtMs = null,
        apReadyAtMs = null,
        ignitionOnAtMs = null,
        activityStartedAtMs = null,
        sessionReadyAtMs = null,
        sessionReadySource = null,
        surfaceReadyAtMs = null,
        apAbsentToPresentAtMs = null,
        timeline = listOf(WakeEvent(trigger, attemptId)),
    )
}
