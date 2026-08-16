package com.openautolink.app.transport.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Test

class AsyncBluetoothOutcomeObserverTest {

    @Test
    fun `SDP outcome is timestamped after listener creation and observer runs asynchronously`() {
        val queued = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val observer = AsyncBluetoothOutcomeObserver(
            enqueue = queued::addLast,
            onSdpPublished = { elapsedMs -> events += "observer-sdp@$elapsedMs" },
            onPhoneDialback = {},
            onFailure = { throw AssertionError(it) },
        )

        events += "listener-created"
        observer.sdpPublishedAt(100L)
        events += "accept-entered"

        assertEquals(listOf("listener-created", "accept-entered"), events)
        queued.removeFirst().invoke()
        assertEquals(
            listOf("listener-created", "accept-entered", "observer-sdp@100"),
            events,
        )
    }

    @Test
    fun `dialback outcome returns to handshake path before slow observer runs`() {
        val queued = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val observer = AsyncBluetoothOutcomeObserver(
            enqueue = queued::addLast,
            onSdpPublished = {},
            onPhoneDialback = { elapsedMs -> events += "observer-dialback@$elapsedMs" },
            onFailure = { throw AssertionError(it) },
        )

        events += "accept-returned-client"
        observer.phoneDialbackAt(200L)
        events += "handshake-dispatched"

        assertEquals(listOf("accept-returned-client", "handshake-dispatched"), events)
        queued.removeFirst().invoke()
        assertEquals(
            listOf("accept-returned-client", "handshake-dispatched", "observer-dialback@200"),
            events,
        )
    }

    @Test
    fun `observer exception is isolated inside queued diagnostic work`() {
        val queued = ArrayDeque<() -> Unit>()
        val failures = mutableListOf<String>()
        val observer = AsyncBluetoothOutcomeObserver(
            enqueue = queued::addLast,
            onSdpPublished = { error("boom") },
            onPhoneDialback = {},
            onFailure = { failures += it.javaClass.simpleName },
        )

        observer.sdpPublishedAt(300L)
        queued.removeFirst().invoke()

        assertEquals(listOf("IllegalStateException"), failures)
    }
}
