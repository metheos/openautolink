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
    fun `non observational GM summary retains default deadline`() {
        val harness = Harness()
        val summary = summary(attemptId = 2L, WakeSignal.GM_SYSTEM_STATE).copy(
            gmState = "raw=3,name=HMI_INACTIVE",
        )

        harness.scheduler.observe(current = summary, previous = null)

        assertEquals(15_000L, harness.scheduledDelaysMs.getValue(2L))
    }

    @Test
    fun `process start and ignition first attempts retain fifteen second deadline`() {
        listOf(WakeSignal.PROCESS_START, WakeSignal.IGNITION_ON).forEachIndexed { index, trigger ->
            val attemptId = 10L + index
            val harness = Harness()

            harness.scheduler.observe(summary(attemptId, trigger), previous = null)

            assertEquals(15_000L, harness.scheduledDelaysMs.getValue(attemptId))
        }
    }

    @Test
    fun `prewake without ignition times out exactly once at sixty seconds`() {
        val harness = Harness()
        val prewake = observationalGmSummary(attemptId = 12L)

        harness.scheduler.observe(prewake, previous = null)
        assertEquals(PRE_WAKE_ATTEMPT_SUMMARY_TIMEOUT_MS, harness.scheduledDelaysMs.getValue(12L))

        harness.advanceTo(PRE_WAKE_ATTEMPT_SUMMARY_TIMEOUT_MS - 1L)
        assertTrue(harness.emitted.isEmpty())
        harness.advanceTo(PRE_WAKE_ATTEMPT_SUMMARY_TIMEOUT_MS)
        harness.callback(12L).invoke()

        assertEquals(listOf(12L to AttemptSummaryOutcome.TIMEOUT), harness.emitted)
        assertEquals(0, harness.scheduler.activeTimeoutCount)
    }

    @Test
    fun `noisy observations do not refresh the prewake deadline`() {
        val harness = Harness()
        val prewake = observationalGmSummary(attemptId = 13L)

        harness.scheduler.observe(prewake, previous = null)
        val originalDueAtMs = harness.dueAtMs.getValue(13L)
        repeat(100) { index ->
            harness.advanceTo(index + 1L)
            harness.scheduler.observe(
                current = prewake.copy(
                    timeline = prewake.timeline +
                        WakeEvent(
                            WakeSignal.GM_SYSTEM_STATE,
                            elapsedMs = index + 1L,
                            detail = "raw=9,name=NOISE_$index",
                        ),
                ),
                previous = null,
            )
        }

        assertEquals(1, harness.scheduleCounts.getValue(13L))
        assertEquals(originalDueAtMs, harness.dueAtMs.getValue(13L))
        harness.advanceTo(PRE_WAKE_ATTEMPT_SUMMARY_TIMEOUT_MS)
        assertEquals(listOf(13L to AttemptSummaryOutcome.TIMEOUT), harness.emitted)
    }

    @Test
    fun `prewake attempt remains open through measured ignition delay and emits ready once`() {
        var nextId = 436L
        val reducer = WakeAttemptReducer { nextId++ }
        val harness = Harness()

        fun record(event: WakeEvent): WakeSummary {
            harness.advanceTo(event.elapsedMs)
            val current = reducer.record(event)
            harness.scheduler.observe(current, reducer.previousSummary)
            return current
        }

        val priorDrive = record(WakeEvent(WakeSignal.IGNITION_ON, elapsedMs = 1_000L))
        assertTrue(harness.scheduler.ready(priorDrive))
        harness.emitted.clear()
        harness.emittedSummaries.clear()
        record(WakeEvent(WakeSignal.IGNITION_OFF, elapsedMs = 2_000L))
        val prewake = record(
            WakeEvent(
                WakeSignal.GM_SYSTEM_STATE,
                elapsedMs = 10_000L,
                detail = "raw=1,name=ANIMATION_INIT",
            )
        )

        harness.advanceTo(25_000L)

        assertTrue(harness.emitted.isEmpty())
        val ignition = record(WakeEvent(WakeSignal.IGNITION_ON, elapsedMs = 39_620L))
        val sessionReady = record(
            WakeEvent(
                WakeSignal.SESSION_READY,
                elapsedMs = 47_507L,
                detail = "ready=true,source=native-session-started",
            )
        )
        assertEquals(prewake.attemptId, ignition.attemptId)
        assertTrue(harness.scheduler.ready(sessionReady))
        assertEquals(
            listOf(prewake.attemptId to AttemptSummaryOutcome.READY),
            harness.emitted,
        )
        assertEquals("native-session-started", harness.emittedSummaries.single().sessionReadySource)
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
        assertEquals(10L, harness.emittedSummaries.single().attemptId)
        assertEquals(setOf(10L, 11L), harness.scheduler.retainedAttemptIds)
        assertEquals(1, harness.scheduler.activeTimeoutCount)
    }

    @Test
    fun `ready racing timeout emits exactly once and cancels that attempt timer`() {
        val harness = Harness()
        val summary = observationalGmSummary(attemptId = 20L)

        harness.scheduler.observe(current = summary, previous = null)
        assertEquals(PRE_WAKE_ATTEMPT_SUMMARY_TIMEOUT_MS, harness.scheduledDelaysMs.getValue(20L))
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
        var nowMs = 0L
        val callbacks = mutableMapOf<Long, () -> Unit>()
        val dueAtMs = mutableMapOf<Long, Long>()
        val scheduledDelaysMs = mutableMapOf<Long, Long>()
        val scheduleCounts = mutableMapOf<Long, Int>()
        val cancelled = mutableMapOf<Long, Boolean>()
        val emitted = mutableListOf<Pair<Long, AttemptSummaryOutcome>>()
        val emittedSummaries = mutableListOf<WakeSummary>()
        val scheduler = AttemptSummaryScheduler(
            timeoutMs = 15_000L,
            timeoutSelector = { summary -> selectAttemptSummaryTimeoutMs(summary, 15_000L) },
            schedule = { attemptId, delayMs, callback ->
                callbacks[attemptId] = callback
                scheduledDelaysMs[attemptId] = delayMs
                scheduleCounts[attemptId] = scheduleCounts.getOrDefault(attemptId, 0) + 1
                dueAtMs[attemptId] = nowMs + delayMs
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

        fun advanceTo(targetMs: Long) {
            require(targetMs >= nowMs)
            nowMs = targetMs
            dueAtMs
                .filter { (attemptId, dueAt) ->
                    dueAt <= targetMs && cancelled[attemptId] == false
                }
                .keys
                .toList()
                .forEach { attemptId ->
                    cancelled[attemptId] = true
                    callback(attemptId).invoke()
                }
        }
    }

    private fun observationalGmSummary(attemptId: Long): WakeSummary =
        summary(attemptId, WakeSignal.GM_SYSTEM_STATE).copy(
            gmState = "raw=1,name=ANIMATION_INIT",
        )

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
