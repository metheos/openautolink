package com.openautolink.app.transport.aasdk

/**
 * Tracks which companion address the current TCP session was opened for.
 *
 * The target must be recorded at the shared [AasdkSession.startTcp] boundary,
 * not only in [AasdkSession.dialCompanion]. WPP restart can call startTcp
 * directly; if that path is not recorded, the next Bluetooth handshake looks
 * like a new phone and replaces the live session it just established.
 */
internal class CompanionDialState {
    @Volatile
    private var startTcpTarget: String? = null

    fun recordStartTcpTarget(target: String?) {
        startTcpTarget = target?.takeIf { it.isNotBlank() }
    }

    fun shouldIgnoreRedial(requestedIp: String, hasLiveTransport: Boolean): Boolean =
        hasLiveTransport && requestedIp == startTcpTarget
}
