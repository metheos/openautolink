package com.openautolink.app.wake

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
    fun `GM init states begin observational attempts without implying readiness`() {
        listOf("ANIMATION_INIT", "HMI_INIT").forEachIndexed { index, gmState ->
            val reducer = WakeAttemptReducer { 90L + index }

            val summary = reducer.record(
                WakeEvent(WakeSignal.GM_SYSTEM_STATE, elapsedMs = 10L, detail = gmState)
            )

            assertEquals(WakeSignal.GM_SYSTEM_STATE, summary.trigger)
            assertEquals(gmState, summary.gmState)
            assertNull(summary.sessionReadyAtMs)
            assertNull(summary.surfaceReadyAtMs)
            assertNull(summary.activityStartedAtMs)
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
}
