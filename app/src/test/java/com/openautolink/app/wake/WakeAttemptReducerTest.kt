package com.openautolink.app.wake

import com.openautolink.app.diagnostics.fitLocalDiagnosticMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeAttemptReducerTest {

    @Test
    fun `AP present before ignition is retained in the same attempt`() {
        var nextId = 40L
        val reducer = WakeAttemptReducer { nextId++ }

        reducer.record(WakeEvent(WakeSignal.AP_ABSENT, elapsedMs = 90L))
        val beforeIgnition = reducer.record(WakeEvent(WakeSignal.AP_PRESENT, elapsedMs = 100L))
        val afterIgnition = reducer.record(WakeEvent(WakeSignal.IGNITION_ON, elapsedMs = 200L))

        assertEquals(beforeIgnition.attemptId, afterIgnition.attemptId)
        assertEquals(40L, afterIgnition.attemptId)
        assertEquals(WakeSignal.IGNITION_ON, afterIgnition.trigger)
        assertEquals(100L, afterIgnition.apReadyAtMs)
        assertEquals(100L, afterIgnition.apAbsentToPresentAtMs)
        assertEquals(200L, afterIgnition.ignitionOnAtMs)
        assertEquals(41L, nextId)
    }

    @Test
    fun `AP present without an observed absent level does not claim an edge`() {
        val reducer = WakeAttemptReducer { 7L }

        val summary = reducer.record(WakeEvent(WakeSignal.AP_PRESENT, elapsedMs = 100L))

        assertEquals(100L, summary.apReadyAtMs)
        assertNull(summary.apAbsentToPresentAtMs)
    }

    @Test
    fun `ignition on anchors a drive attempt without a GM broadcast`() {
        val reducer = WakeAttemptReducer { 81L }

        val summary = reducer.record(WakeEvent(WakeSignal.IGNITION_ON, elapsedMs = 1_000L))

        assertEquals(81L, summary.attemptId)
        assertEquals(WakeSignal.IGNITION_ON, summary.trigger)
        assertNull(summary.gmState)
        assertEquals(1_000L, summary.ignitionOnAtMs)
    }

    @Test
    fun `policy GM init states begin observational attempts without implying readiness`() {
        val policy = PreWakeSignalPolicy()
        listOf(1 to "ANIMATION_INIT", 2 to "HMI_INIT").forEachIndexed { index, (raw, name) ->
            val reducer = WakeAttemptReducer { 90L + index }
            reducer.record(WakeEvent(WakeSignal.PROCESS_START, elapsedMs = 1L))

            val producedEvent = policy.gmSystemState(raw, elapsedMs = 10L).event!!
            val summary = reducer.record(producedEvent)

            assertEquals(WakeSignal.GM_SYSTEM_STATE, summary.trigger)
            assertEquals("raw=$raw,name=$name", summary.gmState)
            assertNull(summary.sessionReadyAtMs)
            assertNull(summary.surfaceReadyAtMs)
            assertNull(summary.activityStartedAtMs)
        }
    }

    @Test
    fun `GM detail matching rejects bare and substring lookalikes`() {
        listOf(
            "ANIMATION_INIT",
            "HMI_INIT",
            "raw=11,name=ANIMATION_INIT",
            "raw=1,name=ANIMATION_INIT_EXTRA",
            "prefix=HMI_INIT,raw=2",
        ).forEachIndexed { index, detail ->
            val reducer = WakeAttemptReducer { 95L + index }
            reducer.record(WakeEvent(WakeSignal.PROCESS_START, elapsedMs = 1L))

            val summary = reducer.record(
                WakeEvent(WakeSignal.GM_SYSTEM_STATE, elapsedMs = 2L, detail = detail)
            )

            assertEquals("detail=$detail", WakeSignal.PROCESS_START, summary.trigger)
        }
    }

    @Test
    fun `timeline ordering uses elapsed realtime instead of input order`() {
        val reducer = WakeAttemptReducer { 101L }

        reducer.record(WakeEvent(WakeSignal.ACTIVITY_START, elapsedMs = 300L))
        reducer.record(WakeEvent(WakeSignal.AP_PRESENT, elapsedMs = 200L))
        val summary = reducer.record(WakeEvent(WakeSignal.IGNITION_ON, elapsedMs = 100L))

        assertEquals(
            listOf(WakeSignal.IGNITION_ON, WakeSignal.AP_PRESENT, WakeSignal.ACTIVITY_START),
            summary.timeline.map { it.signal }
        )
        assertEquals(listOf(100L, 200L, 300L), summary.timeline.map { it.elapsedMs })
    }

    @Test
    fun `missing stages remain explicit and are never inferred`() {
        val reducer = WakeAttemptReducer { 102L }

        reducer.record(WakeEvent(WakeSignal.IGNITION_ON, elapsedMs = 100L))
        reducer.record(WakeEvent(WakeSignal.ACTIVITY_RESUME, elapsedMs = 200L))
        val summary = reducer.record(WakeEvent(WakeSignal.SURFACE_READY, elapsedMs = 300L))

        assertNull(summary.btReadyAtMs)
        assertNull(summary.apReadyAtMs)
        assertNull(summary.activityStartedAtMs)
        assertNull(summary.sessionReadyAtMs)
        assertEquals(300L, summary.surfaceReadyAtMs)
    }

    @Test
    fun `AP absent after shutdown rolls into the next wake attempt`() {
        var nextId = 200L
        val reducer = WakeAttemptReducer { nextId++ }

        reducer.record(WakeEvent(WakeSignal.IGNITION_ON, elapsedMs = 100L))
        reducer.record(WakeEvent(WakeSignal.IGNITION_OFF, elapsedMs = 200L))
        reducer.record(WakeEvent(WakeSignal.AP_ABSENT, elapsedMs = 250L))
        val summary = reducer.record(WakeEvent(WakeSignal.AP_PRESENT, elapsedMs = 300L))

        assertEquals(201L, summary.attemptId)
        assertEquals(300L, summary.apAbsentToPresentAtMs)
        assertEquals(
            listOf(WakeSignal.AP_ABSENT, WakeSignal.AP_PRESENT),
            summary.timeline.map { it.signal }
        )
        assertFalse(reducer.previousSummary!!.timeline.any { it.signal == WakeSignal.AP_ABSENT })
    }

    @Test
    fun `late event from the previous attempt cannot corrupt the current attempt`() {
        var nextId = 210L
        val reducer = WakeAttemptReducer { nextId++ }

        reducer.record(WakeEvent(WakeSignal.IGNITION_ON, elapsedMs = 100L))
        reducer.record(WakeEvent(WakeSignal.IGNITION_OFF, elapsedMs = 200L))
        reducer.record(WakeEvent(WakeSignal.IGNITION_ON, elapsedMs = 300L))
        val summary = reducer.record(WakeEvent(WakeSignal.SESSION_READY, elapsedMs = 150L))

        assertEquals(211L, summary.attemptId)
        assertNull(summary.sessionReadyAtMs)
        assertEquals(listOf(WakeSignal.IGNITION_ON), summary.timeline.map { it.signal })
        assertEquals(150L, reducer.previousSummary!!.sessionReadyAtMs)
    }

    @Test
    fun `Bluetooth off after shutdown rolls into the next wake attempt`() {
        var nextId = 220L
        val reducer = WakeAttemptReducer { nextId++ }

        reducer.record(WakeEvent(WakeSignal.IGNITION_ON, elapsedMs = 100L))
        reducer.record(WakeEvent(WakeSignal.IGNITION_OFF, elapsedMs = 200L))
        reducer.record(WakeEvent(WakeSignal.BLUETOOTH_OFF, elapsedMs = 250L))
        val summary = reducer.record(WakeEvent(WakeSignal.BLUETOOTH_ON, elapsedMs = 300L))

        assertEquals(221L, summary.attemptId)
        assertEquals(
            listOf(WakeSignal.BLUETOOTH_OFF, WakeSignal.BLUETOOTH_ON),
            summary.timeline.map { it.signal }
        )
        assertFalse(
            reducer.previousSummary!!.timeline.any { it.signal == WakeSignal.BLUETOOTH_OFF }
        )
    }

    @Test
    fun `history is bounded to the current attempt and previous summary`() {
        var nextId = 200L
        val reducer = WakeAttemptReducer { nextId++ }

        reducer.record(WakeEvent(WakeSignal.IGNITION_ON, elapsedMs = 100L))
        reducer.record(WakeEvent(WakeSignal.IGNITION_OFF, elapsedMs = 200L))
        reducer.record(WakeEvent(WakeSignal.IGNITION_ON, elapsedMs = 300L))
        reducer.record(WakeEvent(WakeSignal.IGNITION_OFF, elapsedMs = 400L))
        reducer.record(WakeEvent(WakeSignal.IGNITION_ON, elapsedMs = 500L))

        assertEquals(202L, reducer.currentSummary!!.attemptId)
        assertEquals(201L, reducer.previousSummary!!.attemptId)
        assertFalse(reducer.previousSummary!!.timeline.any { it.elapsedMs < 300L })
    }

    @Test
    fun `hundreds of GM observations keep bounded critical timeline and correct rollover`() {
        var nextId = 300L
        val reducer = WakeAttemptReducer { nextId++ }

        reducer.record(WakeEvent(WakeSignal.IGNITION_ON, 100L))
        reducer.record(WakeEvent(WakeSignal.BLUETOOTH_ON, 110L))
        reducer.record(WakeEvent(WakeSignal.AP_PRESENT, 120L))
        reducer.record(WakeEvent(WakeSignal.ACTIVITY_START, 130L))
        reducer.record(WakeEvent(WakeSignal.SESSION_READY, 140L))
        reducer.record(WakeEvent(WakeSignal.SURFACE_READY, 150L))
        repeat(500) { index ->
            reducer.record(
                WakeEvent(
                    WakeSignal.GM_SYSTEM_STATE,
                    elapsedMs = 200L + index,
                    detail = "raw=${index % 9},name=NOISE_$index",
                )
            )
        }
        reducer.record(WakeEvent(WakeSignal.IGNITION_OFF, 800L))
        reducer.record(WakeEvent(WakeSignal.AP_ABSENT, 850L))
        reducer.record(WakeEvent(WakeSignal.BLUETOOTH_OFF, 860L))
        val current = reducer.record(WakeEvent(WakeSignal.AP_PRESENT, 900L))
        val completed = reducer.previousSummary!!

        assertTrue(completed.timeline.size <= WakeAttemptReducer.MAX_TIMELINE_EVENTS)
        assertEquals(110L, completed.btReadyAtMs)
        assertEquals(120L, completed.apReadyAtMs)
        assertEquals(130L, completed.activityStartedAtMs)
        assertEquals(140L, completed.sessionReadyAtMs)
        assertEquals(150L, completed.surfaceReadyAtMs)
        assertTrue(completed.timeline.any { it.signal == WakeSignal.IGNITION_OFF && it.elapsedMs == 800L })
        assertEquals(301L, current.attemptId)
        assertEquals(900L, current.apAbsentToPresentAtMs)
        assertTrue(current.timeline.any { it.signal == WakeSignal.BLUETOOTH_OFF })
    }

    @Test
    fun `compaction does not synthesize an AP edge from present only`() {
        val reducer = WakeAttemptReducer { 401L }
        reducer.record(WakeEvent(WakeSignal.AP_PRESENT, 10L))
        addCompactionNoise(reducer, startingAtMs = 100L)

        val summary = reducer.currentSummary!!
        assertEquals(10L, summary.apReadyAtMs)
        assertNull(summary.apAbsentToPresentAtMs)
    }

    @Test
    fun `compaction does not synthesize an AP edge when absent has no later present`() {
        val reducer = WakeAttemptReducer { 402L }
        reducer.record(WakeEvent(WakeSignal.AP_PRESENT, 10L))
        reducer.record(WakeEvent(WakeSignal.AP_ABSENT, 20L))
        addCompactionNoise(reducer, startingAtMs = 100L)

        val summary = reducer.currentSummary!!
        assertEquals(10L, summary.apReadyAtMs)
        assertNull(summary.apAbsentToPresentAtMs)
    }

    @Test
    fun `compaction retains a true absent to present edge`() {
        val reducer = WakeAttemptReducer { 403L }
        reducer.record(WakeEvent(WakeSignal.AP_ABSENT, 10L))
        reducer.record(WakeEvent(WakeSignal.AP_PRESENT, 20L))
        addCompactionNoise(reducer, startingAtMs = 100L)

        assertEquals(20L, reducer.currentSummary!!.apAbsentToPresentAtMs)
    }

    @Test
    fun `compaction retains the first true AP edge across multiple transitions`() {
        val reducer = WakeAttemptReducer { 404L }
        reducer.record(WakeEvent(WakeSignal.AP_PRESENT, 5L))
        reducer.record(WakeEvent(WakeSignal.AP_ABSENT, 10L))
        reducer.record(WakeEvent(WakeSignal.AP_PRESENT, 20L))
        reducer.record(WakeEvent(WakeSignal.AP_ABSENT, 30L))
        reducer.record(WakeEvent(WakeSignal.AP_PRESENT, 40L))
        addCompactionNoise(reducer, startingAtMs = 100L)

        assertEquals(20L, reducer.currentSummary!!.apAbsentToPresentAtMs)
    }

    @Test
    fun `compaction preserves AP precursor across an ignition rollover boundary`() {
        var nextId = 405L
        val reducer = WakeAttemptReducer { nextId++ }
        reducer.record(WakeEvent(WakeSignal.IGNITION_ON, 10L))
        reducer.record(WakeEvent(WakeSignal.IGNITION_OFF, 50L))
        repeat(WakeAttemptReducer.MAX_TIMELINE_EVENTS + 20) { index ->
            val signal = if (index % 2 == 0) WakeSignal.AP_ABSENT else WakeSignal.BLUETOOTH_OFF
            reducer.record(WakeEvent(signal, 60L + index))
        }

        val summary = reducer.record(WakeEvent(WakeSignal.AP_PRESENT, 200L))

        assertEquals(406L, summary.attemptId)
        assertEquals(200L, summary.apAbsentToPresentAtMs)
        assertFalse(reducer.previousSummary!!.timeline.any { it.elapsedMs > 50L })
    }

    @Test
    fun `formatter emits one stable WAKE SUMMARY line with explicit missing values`() {
        val reducer = WakeAttemptReducer { 9L }
        reducer.record(WakeEvent(WakeSignal.AP_PRESENT, elapsedMs = 120L))
        val summary = reducer.record(WakeEvent(WakeSignal.IGNITION_ON, elapsedMs = 200L))

        val line = WakeSummaryFormatter.format(summary)

        assertEquals(
            "WAKE SUMMARY attempt=9 trigger=IGNITION_ON gm=- bt=- ap=120 " +
                "apEdge=- ignition=200 activity=- session=- surface=- " +
                "timeline=AP_PRESENT@120>IGNITION_ON@200",
            line
        )
        assertFalse(line.contains('\n'))
        assertTrue(line.startsWith("WAKE SUMMARY "))
    }

    @Test
    fun `diagnostic summary bounds timeline before the sink can erase terminal evidence`() {
        val summary = WakeSummary(
            attemptId = 12L,
            trigger = WakeSignal.GM_SYSTEM_STATE,
            gmState = "raw=1,name=ANIMATION_INIT",
            btReadyAtMs = 20L,
            apReadyAtMs = 30L,
            ignitionOnAtMs = 40L,
            activityStartedAtMs = 50L,
            sessionReadyAtMs = 60L,
            surfaceReadyAtMs = 70L,
            apAbsentToPresentAtMs = 30L,
            timeline = List(WakeAttemptReducer.MAX_TIMELINE_EVENTS) { index ->
                WakeEvent(
                    WakeSignal.GM_SYSTEM_STATE,
                    elapsedMs = 100L + index,
                    detail = "raw=$index,name=${"X".repeat(140)}",
                )
            },
        )
        val gmEvidence =
            "gmSystemState=observed gmPowerMode=not_observed " +
                "gmPoweroffView=observed gmHomeStarted=not_observed"

        val line = WakeSummaryFormatter.formatForDiagnosticLog(
            summary = summary,
            gmEvidenceFields = gmEvidence,
            outcome = "timeout",
            missing = "none",
        )
        val stored = fitLocalDiagnosticMessage(line)

        assertTrue("summary length=${line.length}", line.length <= 480)
        assertEquals(line, stored)
        listOf(
            "attempt=12",
            "trigger=GM_SYSTEM_STATE",
            "gm=raw=1,name=ANIMATION_INIT",
            "bt=20",
            "ap=30",
            "apEdge=30",
            "ignition=40",
            "activity=50",
            "session=60",
            "surface=70",
            "gmSystemState=observed",
            "gmPowerMode=not_observed",
            "gmPoweroffView=observed",
            "gmHomeStarted=not_observed",
            "outcome=timeout",
            "missing=none",
        ).forEach { required -> assertTrue("missing $required", stored.contains(required)) }
        assertTrue(stored.indexOf("missing=none") < stored.indexOf("timeline="))
    }

    private fun addCompactionNoise(reducer: WakeAttemptReducer, startingAtMs: Long) {
        repeat(WakeAttemptReducer.MAX_TIMELINE_EVENTS + 20) { index ->
            reducer.record(
                WakeEvent(
                    signal = WakeSignal.GM_SYSTEM_STATE,
                    elapsedMs = startingAtMs + index,
                    detail = "raw=9,name=NOISE_$index",
                )
            )
        }
    }
}
