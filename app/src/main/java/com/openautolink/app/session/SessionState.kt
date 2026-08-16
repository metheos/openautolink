package com.openautolink.app.session

import com.openautolink.app.transport.ConnectionState

/**
 * Session states — maps the full lifecycle from idle to streaming.
 */
enum class SessionState {
    IDLE,              // No connection, waiting for phone
    CONNECTING,        // Phone connecting (AA handshake in progress)
    CONNECTED,         // AA handshake complete, channels opening
    STREAMING,         // Video and/or audio actively flowing
    ERROR              // Unrecoverable error (shows message, user can retry)
}

/**
 * Maps transport ConnectionState to session-level state.
 */
fun ConnectionState.toSessionState(): SessionState = when (this) {
    ConnectionState.DISCONNECTED -> SessionState.IDLE
    ConnectionState.CONNECTING -> SessionState.CONNECTING
    ConnectionState.CONNECTED -> SessionState.CONNECTED
    ConnectionState.PHONE_CONNECTED -> SessionState.STREAMING
    ConnectionState.STREAMING -> SessionState.STREAMING
}

fun shouldStartStreamingServices(
    current: SessionState,
    reported: SessionState,
): Boolean = current != SessionState.STREAMING && reported == SessionState.STREAMING

/**
 * Transport callbacks can arrive after the AA control channel has already proved
 * that media is streaming. Preserve that stronger state across a late CONNECTED
 * report, while still honoring reconnects, disconnects, and errors.
 */
fun reconcileTransportSessionState(
    current: SessionState,
    reported: SessionState,
): SessionState = if (
    current == SessionState.STREAMING && reported == SessionState.CONNECTED
) {
    SessionState.STREAMING
} else {
    reported
}
