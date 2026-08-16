package com.openautolink.app.session

import com.openautolink.app.transport.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStateTest {

    @Test
    fun `ConnectionState DISCONNECTED maps to SessionState IDLE`() {
        assertEquals(SessionState.IDLE, ConnectionState.DISCONNECTED.toSessionState())
    }

    @Test
    fun `ConnectionState CONNECTING maps to SessionState CONNECTING`() {
        assertEquals(SessionState.CONNECTING, ConnectionState.CONNECTING.toSessionState())
    }

    @Test
    fun `ConnectionState CONNECTED maps to SessionState CONNECTED`() {
        assertEquals(SessionState.CONNECTED, ConnectionState.CONNECTED.toSessionState())
    }

    @Test
    fun `ConnectionState PHONE_CONNECTED maps to SessionState STREAMING`() {
        assertEquals(SessionState.STREAMING, ConnectionState.PHONE_CONNECTED.toSessionState())
    }

    @Test
    fun `ConnectionState STREAMING maps to SessionState STREAMING`() {
        assertEquals(SessionState.STREAMING, ConnectionState.STREAMING.toSessionState())
    }

    @Test
    fun `late transport CONNECTED cannot downgrade an active stream`() {
        assertEquals(
            SessionState.STREAMING,
            reconcileTransportSessionState(SessionState.STREAMING, SessionState.CONNECTED),
        )
    }

    @Test
    fun `suppressed CONNECTED does not restart streaming services`() {
        assertEquals(
            false,
            shouldStartStreamingServices(SessionState.STREAMING, SessionState.CONNECTED),
        )
        assertEquals(
            false,
            shouldStartStreamingServices(SessionState.STREAMING, SessionState.STREAMING),
        )
        assertEquals(
            true,
            shouldStartStreamingServices(SessionState.CONNECTED, SessionState.STREAMING),
        )
    }

    @Test
    fun `transport CONNECTING still leaves an active stream for real recovery`() {
        assertEquals(
            SessionState.CONNECTING,
            reconcileTransportSessionState(SessionState.STREAMING, SessionState.CONNECTING),
        )
    }

    @Test
    fun `transport disconnect and error still leave streaming`() {
        assertEquals(
            SessionState.IDLE,
            reconcileTransportSessionState(SessionState.STREAMING, SessionState.IDLE),
        )
        assertEquals(
            SessionState.ERROR,
            reconcileTransportSessionState(SessionState.STREAMING, SessionState.ERROR),
        )
    }

    @Test
    fun `all ConnectionState values have a mapping`() {
        ConnectionState.entries.forEach { state ->
            // Should not throw
            state.toSessionState()
        }
    }
}
