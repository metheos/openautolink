package com.openautolink.app.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmDeliveryEvidenceTrackerTest {

    @Test
    fun `all four absent signals are explicitly not observed`() {
        val tracker = GmDeliveryEvidenceTracker()
        tracker.retain(currentAttemptId = 10L, previousAttemptId = null)

        assertEquals(
            "gmSystemState=not_observed gmPowerMode=not_observed " +
                "gmPoweroffView=not_observed gmHomeStarted=not_observed",
            tracker.format(10L),
        )
    }

    @Test
    fun `observations attach only to the supplied attempt id`() {
        val tracker = GmDeliveryEvidenceTracker()
        tracker.retain(currentAttemptId = 11L, previousAttemptId = 10L)

        tracker.observe(10L, GmDeliverySignal.SYSTEM_STATE)
        tracker.observe(11L, GmDeliverySignal.POWER_MODE)

        assertEquals(
            "gmSystemState=observed gmPowerMode=not_observed " +
                "gmPoweroffView=not_observed gmHomeStarted=not_observed",
            tracker.format(10L),
        )
        assertEquals(
            "gmSystemState=not_observed gmPowerMode=observed " +
                "gmPoweroffView=not_observed gmHomeStarted=not_observed",
            tracker.format(11L),
        )
    }

    @Test
    fun `retention is bounded to current and previous attempt without leakage`() {
        val tracker = GmDeliveryEvidenceTracker()
        tracker.retain(currentAttemptId = 20L, previousAttemptId = null)
        tracker.observe(20L, GmDeliverySignal.HOME_STARTED)

        tracker.retain(currentAttemptId = 21L, previousAttemptId = 20L)
        tracker.observe(21L, GmDeliverySignal.POWEROFF_VIEW)
        tracker.retain(currentAttemptId = 22L, previousAttemptId = 21L)

        assertEquals(setOf(21L, 22L), tracker.retainedAttemptIds)
        assertFalse(tracker.format(22L).contains("=observed"))
        assertTrue(tracker.format(21L).contains("gmPoweroffView=observed"))
        assertFalse(tracker.format(21L).contains("gmHomeStarted=observed"))
        assertFalse(tracker.format(20L).contains("=observed"))
    }

    @Test
    fun `partial observations leave every other signal explicitly not observed`() {
        val tracker = GmDeliveryEvidenceTracker()
        tracker.retain(currentAttemptId = 30L, previousAttemptId = null)

        tracker.observe(30L, GmDeliverySignal.SYSTEM_STATE)
        tracker.observe(30L, GmDeliverySignal.HOME_STARTED)

        assertEquals(
            "gmSystemState=observed gmPowerMode=not_observed " +
                "gmPoweroffView=not_observed gmHomeStarted=observed",
            tracker.format(30L),
        )
    }

    @Test
    fun `auxiliary GM evidence after ignition off follows elapsed boundary into next attempt`() {
        var nextId = 40L
        val reducer = WakeAttemptReducer { nextId++ }
        val tracker = GmDeliveryEvidenceTracker()

        reducer.record(WakeEvent(WakeSignal.IGNITION_ON, elapsedMs = 100L))
        reducer.record(WakeEvent(WakeSignal.IGNITION_OFF, elapsedMs = 200L))
        tracker.retain(reducer.retainedAttemptWindows())
        tracker.observeAt(elapsedMs = 250L, GmDeliverySignal.POWER_MODE)

        val next = reducer.record(WakeEvent(WakeSignal.IGNITION_ON, elapsedMs = 300L))
        tracker.retain(reducer.retainedAttemptWindows())

        assertFalse(tracker.format(reducer.previousSummary!!.attemptId).contains("=observed"))
        assertTrue(tracker.format(next.attemptId).contains("gmPowerMode=observed"))
    }
}
