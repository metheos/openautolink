package com.openautolink.app.transport.bluetooth

enum class LateCompanionAction {
    DIAL_COMPANION,
    QUEUE_READVERTISE,
    READVERTISE,
    IGNORE,
}

object WppBootstrapPolicy {
    /** Must finish before AaProxy's 30-second pre-warm car-socket waiter. */
    const val DISCOVERY_DEADLINE_MS = 20_000L

    fun onCompanionReachable(
        bootstrapLoopbackPending: Boolean,
        usesReservedProxyPort: Boolean,
        handshakeInFlight: Boolean,
        sessionStreaming: Boolean,
    ): LateCompanionAction = when {
        sessionStreaming || !bootstrapLoopbackPending -> LateCompanionAction.IGNORE
        usesReservedProxyPort -> LateCompanionAction.DIAL_COMPANION
        handshakeInFlight -> LateCompanionAction.QUEUE_READVERTISE
        else -> LateCompanionAction.READVERTISE
    }
}
