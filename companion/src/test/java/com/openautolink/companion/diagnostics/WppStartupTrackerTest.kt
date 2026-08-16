package com.openautolink.companion.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WppStartupTrackerTest {

    @Test
    fun `start and prewarm inputs join one active attempt`() {
        var nextId = 41L
        val tracker = WppStartupTracker { nextId++ }

        val started = tracker.startOrJoin(WppStartInput.START, elapsedMs = 100L)
        val prewarmed = tracker.startOrJoin(WppStartInput.PREWARM, elapsedMs = 110L)
        val duplicateStart = tracker.startOrJoin(WppStartInput.START, elapsedMs = 120L)

        assertEquals(41L, started)
        assertEquals(started, prewarmed)
        assertEquals(started, duplicateStart)
        assertEquals(42L, nextId)
    }

    @Test
    fun `BT disconnect during handoff does not terminate an attempt that bridges`() {
        val tracker = WppStartupTracker { 8L }
        tracker.selectedCarConnected(elapsedMs = 1_000L)

        tracker.record(PhoneWppStage.TARGET_BT_DISCONNECTED, elapsedMs = 1_200L)
        val bridged = tracker.record(PhoneWppStage.BRIDGE_ESTABLISHED, elapsedMs = 1_500L)

        assertTrue(tracker.isActive)
        assertEquals(8L, bridged.attemptId)
        assertEquals("connected", bridged.outcome)
        assertEquals(
            listOf(
                PhoneWppStage.TARGET_BT_CONNECTED,
                PhoneWppStage.TARGET_BT_DISCONNECTED,
                PhoneWppStage.BRIDGE_ESTABLISHED,
            ),
            bridged.timeline.map { it.stage },
        )
    }

    @Test
    fun `connected outcome and missing stage survive bridge eviction from bounded history`() {
        val tracker = WppStartupTracker { 9L }
        tracker.selectedCarConnected(elapsedMs = 1L)
        listOf(
            PhoneWppStage.SERVICE_READY,
            PhoneWppStage.TCP_LISTENING,
            PhoneWppStage.WARM_PROXY_READY,
            PhoneWppStage.NETWORK_AVAILABLE,
            PhoneWppStage.CAR_PROBE,
            PhoneWppStage.CAR_SOCKET,
            PhoneWppStage.AA_SOCKET,
            PhoneWppStage.BRIDGE_ESTABLISHED,
        ).forEachIndexed { index, stage ->
            tracker.record(stage, elapsedMs = 10L + index)
        }
        repeat(WppStartupTracker.MAX_EVENTS + 1) { index ->
            tracker.record(PhoneWppStage.NETWORK_LOST, elapsedMs = 100L + index)
        }

        val summary = tracker.record(PhoneWppStage.STOPPED, elapsedMs = 1_000L)

        assertFalse(summary.timeline.any { it.stage == PhoneWppStage.BRIDGE_ESTABLISHED })
        assertEquals("connected", summary.outcome)
        assertEquals(null, summary.missingStage)
    }

    @Test
    fun `AA socket without car socket waits for car and is never connected`() {
        val tracker = WppStartupTracker { 10L }
        tracker.startOrJoin(WppStartInput.PREWARM, elapsedMs = 100L)

        val summary = tracker.record(PhoneWppStage.AA_SOCKET, elapsedMs = 200L)

        assertEquals("waiting_for_car", summary.outcome)
        assertTrue(summary.outcome != "connected")
    }

    @Test
    fun `waiting outcome and missing stage survive AA socket eviction from bounded history`() {
        val tracker = WppStartupTracker { 11L }
        tracker.selectedCarConnected(elapsedMs = 1L)
        listOf(
            PhoneWppStage.SERVICE_READY,
            PhoneWppStage.TCP_LISTENING,
            PhoneWppStage.WARM_PROXY_READY,
            PhoneWppStage.NETWORK_AVAILABLE,
            PhoneWppStage.CAR_PROBE,
            PhoneWppStage.AA_SOCKET,
        ).forEachIndexed { index, stage ->
            tracker.record(stage, elapsedMs = 10L + index)
        }
        repeat(WppStartupTracker.MAX_EVENTS + 1) { index ->
            tracker.record(PhoneWppStage.NETWORK_LOST, elapsedMs = 100L + index)
        }

        val summary = tracker.record(PhoneWppStage.STOPPED, elapsedMs = 1_000L)

        assertFalse(summary.timeline.any { it.stage == PhoneWppStage.AA_SOCKET })
        assertEquals("waiting_for_car", summary.outcome)
        assertEquals(PhoneWppStage.CAR_SOCKET, summary.missingStage)
    }

    @Test
    fun `car socket without AA socket waits for Android Auto`() {
        val tracker = WppStartupTracker { 10L }
        tracker.selectedCarConnected(elapsedMs = 100L)

        val summary = tracker.record(PhoneWppStage.CAR_SOCKET, elapsedMs = 200L)

        assertEquals("waiting_for_android_auto", summary.outcome)
    }

    @Test
    fun `only bridge establishment completes with a positive connected outcome`() {
        val socketsOnly = WppStartupTracker { 11L }
        socketsOnly.startOrJoin(WppStartInput.START, elapsedMs = 10L)
        socketsOnly.record(PhoneWppStage.CAR_SOCKET, elapsedMs = 20L)
        socketsOnly.record(PhoneWppStage.AA_SOCKET, elapsedMs = 30L)

        val incomplete = socketsOnly.complete(elapsedMs = 40L)

        assertTrue(incomplete.outcome != "connected")

        val bridged = WppStartupTracker { 12L }
        bridged.startOrJoin(WppStartInput.START, elapsedMs = 50L)
        bridged.record(PhoneWppStage.BRIDGE_ESTABLISHED, elapsedMs = 60L)

        assertEquals("connected", bridged.complete(elapsedMs = 70L).outcome)
    }

    @Test
    fun `timeout identifies the first missing startup stage`() {
        val tracker = WppStartupTracker { 13L }
        tracker.selectedCarConnected(elapsedMs = 10L)
        tracker.record(PhoneWppStage.SERVICE_READY, elapsedMs = 20L)
        tracker.record(PhoneWppStage.TCP_LISTENING, elapsedMs = 30L)

        val timedOut = tracker.timeout(elapsedMs = 1_000L)

        assertEquals("timeout", timedOut.outcome)
        assertEquals(PhoneWppStage.WARM_PROXY_READY, timedOut.missingStage)
        assertTrue(!tracker.isActive)
    }

    @Test
    fun `injected attempt IDs must be positive before an attempt activates`() {
        listOf(-1L, 0L).forEach { suppliedId ->
            val tracker = WppStartupTracker { suppliedId }

            assertThrows(IllegalArgumentException::class.java) {
                tracker.startOrJoin(WppStartInput.START, elapsedMs = 1L)
            }
            assertFalse(tracker.isActive)
        }
    }

    @Test
    fun `duplicate and decreasing injected attempt IDs throw before activation`() {
        val suppliedIds = mutableListOf(7L, 7L, 6L, 8L)
        val tracker = WppStartupTracker { suppliedIds.removeAt(0) }
        assertEquals(7L, tracker.startOrJoin(WppStartInput.START, elapsedMs = 1L))
        tracker.timeout(elapsedMs = 2L)

        assertThrows(IllegalArgumentException::class.java) {
            tracker.startOrJoin(WppStartInput.START, elapsedMs = 3L)
        }
        assertFalse(tracker.isActive)
        assertThrows(IllegalArgumentException::class.java) {
            tracker.startOrJoin(WppStartInput.START, elapsedMs = 4L)
        }
        assertFalse(tracker.isActive)
        assertEquals(8L, tracker.startOrJoin(WppStartInput.START, elapsedMs = 5L))
    }

    @Test
    fun `default counter fails closed at Long MAX and never wraps`() {
        val counter = MonotonicAttemptIdCounter(initialValue = Long.MAX_VALUE - 1L)

        assertEquals(Long.MAX_VALUE, counter.next())
        repeat(2) {
            assertThrows(IllegalStateException::class.java) { counter.next() }
        }
    }

    @Test
    fun `selected car connect after terminal attempts creates a new monotonic ID`() {
        var nextId = 21L
        val tracker = WppStartupTracker { nextId++ }

        assertEquals(21L, tracker.selectedCarConnected(elapsedMs = 10L))
        tracker.record(PhoneWppStage.BRIDGE_ESTABLISHED, elapsedMs = 20L)
        tracker.complete(elapsedMs = 30L)

        assertEquals(22L, tracker.selectedCarConnected(elapsedMs = 40L))
        tracker.timeout(elapsedMs = 50L)

        assertEquals(23L, tracker.selectedCarConnected(elapsedMs = 60L))
    }

    @Test
    fun `event history and sanitized details are bounded without credentials`() {
        val tracker = WppStartupTracker { 30L }
        tracker.selectedCarConnected(elapsedMs = 1L)

        repeat(200) { index ->
            tracker.record(
                PhoneWppStage.CAR_PROBE,
                elapsedMs = 10L + index,
                detail = "probe=$index ssid=PrivateNet password=superSecret " + "x".repeat(200),
            )
        }
        val summary = tracker.timeout(elapsedMs = 1_000L)
        val renderedDetails = summary.timeline.joinToString("|") { it.detail }

        assertTrue(summary.timeline.size <= WppStartupTracker.MAX_EVENTS)
        assertTrue(summary.timeline.all { it.detail.length <= WppStartupTracker.MAX_DETAIL_LENGTH })
        assertFalse(renderedDetails.contains("PrivateNet"))
        assertFalse(renderedDetails.contains("superSecret"))
    }

    @Test
    fun `sanitizer drops all free-form identifiers addresses and credentials`() {
        val tracker = WppStartupTracker { 31L }
        tracker.selectedCarConnected(elapsedMs = 1L, detail = "peer-identifier-12345")
        listOf(
            "ssid=My Home Network, token=abc 123; mode=ready",
            "PSK 'hunter two'",
            "password=\"space separated secret\"",
            "credential unquoted value",
            "client secret: do-not-log",
            "peer aa:bb:cc:dd:ee:ff",
            "route 192.168.1.10",
            "route fe80::1%wlan0",
        ).forEachIndexed { index, detail ->
            tracker.record(PhoneWppStage.CAR_PROBE, elapsedMs = 10L + index, detail = detail)
        }

        val summary = tracker.timeout(elapsedMs = 100L)

        assertTrue(summary.timeline.all { it.detail.isEmpty() })
    }

    @Test
    fun `summary formatter keeps outcome and missing before a bounded one line timeline`() {
        val tracker = WppStartupTracker { 31L }
        tracker.selectedCarConnected(elapsedMs = 1L)
        repeat(WppStartupTracker.MAX_EVENTS) { index ->
            tracker.record(
                PhoneWppStage.CAR_PROBE,
                elapsedMs = 10L + index,
                detail = "probe=$index ${"y".repeat(80)}",
            )
        }
        val summary = tracker.timeout(elapsedMs = 1_000L)

        val line = PhoneWppSummaryFormatter.format(summary)

        assertTrue(line.startsWith("PHONE WPP SUMMARY attempt=31 "))
        assertTrue(line.length <= PhoneWppSummaryFormatter.MAX_LINE_LENGTH)
        assertFalse(line.contains('\n'))
        assertTrue(line.contains("outcome=timeout"))
        assertTrue(line.contains("missing=SERVICE_READY"))
        assertTrue(line.indexOf("outcome=") < line.indexOf("timeline="))
        assertTrue(line.indexOf("missing=") < line.indexOf("timeline="))
    }

    @Test
    fun `lifecycle summary retains supplied elapsed realtime and joined inputs`() {
        val tracker = WppStartupTracker { 32L }
        tracker.startOrJoin(WppStartInput.START, elapsedMs = 100L)
        tracker.startOrJoin(WppStartInput.PREWARM, elapsedMs = 120L)

        val summary = tracker.timeout(elapsedMs = 300L)

        assertEquals(100L, summary.startedAtMs)
        assertEquals(300L, summary.endedAtMs)
        assertEquals(setOf(WppStartInput.START, WppStartInput.PREWARM), summary.startInputs)
    }
}
