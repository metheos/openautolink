package com.openautolink.app.ui.projection

/** Pure admission policy for rebuilding an idle projection session after wake. */
internal object WppWakeReconnectPolicy {
    fun cooldownAllows(
        wppSelected: Boolean,
        elapsedSinceAttemptMs: Long,
        minimumGapMs: Long,
    ): Boolean = wppSelected || elapsedSinceAttemptMs >= minimumGapMs

    fun preStartRejection(
        wppSelectedNow: Boolean,
        ignitionOff: Boolean,
        sessionIdle: Boolean,
        currentWppOwnerPresent: Boolean = false,
    ): String? = when {
        !wppSelectedNow -> "transport-changed"
        ignitionOff -> "ignition-off"
        !sessionIdle -> "session-not-idle"
        currentWppOwnerPresent -> "session-owner-active"
        else -> null
    }

    fun shouldKickWake(
        wppSelected: Boolean,
        wirelessDiscoveryEnabled: Boolean,
        alwaysAskPhone: Boolean,
        defaultPhonePresent: Boolean,
        resolvedPhonePresent: Boolean,
        connectInFlight: Boolean,
        sessionIdle: Boolean,
        currentWppOwnerPresent: Boolean = false,
    ): Boolean {
        if (connectInFlight || !sessionIdle) return false
        if (wppSelected) return !currentWppOwnerPresent
        return wirelessDiscoveryEnabled &&
            !alwaysAskPhone &&
            defaultPhonePresent &&
            resolvedPhonePresent
    }

    fun shouldKickIgnition(
        wppSelected: Boolean,
        wirelessDiscoveryEnabled: Boolean,
        alwaysAskPhone: Boolean,
        defaultPhonePresent: Boolean,
        connectInFlight: Boolean,
        sessionIdle: Boolean,
        currentWppOwnerPresent: Boolean = false,
    ): Boolean {
        if (connectInFlight || !sessionIdle) return false
        if (wppSelected) return !currentWppOwnerPresent
        return wirelessDiscoveryEnabled &&
            !alwaysAskPhone &&
            defaultPhonePresent
    }
}
