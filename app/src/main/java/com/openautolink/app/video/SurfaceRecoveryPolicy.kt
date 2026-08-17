package com.openautolink.app.video

enum class SurfaceRecoveryAction {
    KEEP_LIVE_CODEC,
    RECONFIGURE_WITH_CACHED_IDR,
    RESTART_SESSION,
}

/** Chooses the least disruptive recovery that can produce a picture. */
object SurfaceRecoveryPolicy {
    fun afterSwap(
        swapSucceeded: Boolean,
        hasCachedRealIdr: Boolean,
    ): SurfaceRecoveryAction = when {
        swapSucceeded -> SurfaceRecoveryAction.KEEP_LIVE_CODEC
        hasCachedRealIdr -> SurfaceRecoveryAction.RECONFIGURE_WITH_CACHED_IDR
        else -> SurfaceRecoveryAction.RESTART_SESSION
    }
}
