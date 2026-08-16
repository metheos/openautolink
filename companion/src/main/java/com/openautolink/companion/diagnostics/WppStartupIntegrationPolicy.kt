package com.openautolink.companion.diagnostics

enum class WppIntegrationTrigger {
    SELECTED_BT,
    START,
    PREWARM,
}

enum class WppNetworkObservationMode {
    PASSIVE,
}

enum class WppAssociationOwner(val logValue: String) {
    COMPANION_ASSIST("companion_assist"),
    WPP("wpp"),
}

/** Pure routing rules shared by the passive Android integration and host tests. */
object WppStartupIntegrationPolicy {
    fun routeAttempt(
        @Suppress("UNUSED_PARAMETER") trigger: WppIntegrationTrigger,
        activeAttemptId: Long?,
        candidateAttemptId: Long,
    ): Long {
        require(candidateAttemptId > 0L) { "WPP candidate attempt ID must be positive" }
        return activeAttemptId ?: candidateAttemptId
    }

    fun permitsActiveNetworkRequest(mode: WppNetworkObservationMode): Boolean = when (mode) {
        WppNetworkObservationMode.PASSIVE -> false
    }

    /** Null means a caller without a diagnostic handoff; zero means diagnostics skipped it. */
    fun shouldJoinDispatchedAttempt(dispatchedAttemptId: Long?): Boolean =
        dispatchedAttemptId == null || dispatchedAttemptId > 0L

    fun isBridgeEstablished(stage: PhoneWppStage): Boolean =
        stage == PhoneWppStage.BRIDGE_ESTABLISHED
}

/** Tracks only networks that produced an accepted addressed-Wi-Fi observation. */
internal class WppObservedNetworkSet<T> {
    private val acceptedNetworks = mutableSetOf<T>()

    fun accept(network: T): Boolean = acceptedNetworks.add(network)

    fun lost(network: T): Boolean = acceptedNetworks.remove(network)

    fun clear() {
        acceptedNetworks.clear()
    }
}

/** Owns timeout and posted-cleanup work for one monotonic diagnostic attempt ID. */
internal class WppAttemptLifecycleGuard {
    private var activeAttemptId: Long? = null
    private var cleanupPendingAttemptId: Long? = null

    fun started(attemptId: Long) {
        require(attemptId > 0L) { "WPP attempt ID must be positive" }
        activeAttemptId = attemptId
    }

    fun completed(attemptId: Long) {
        if (activeAttemptId != attemptId) return
        activeAttemptId = null
        cleanupPendingAttemptId = attemptId
    }

    fun ownsActiveAttempt(attemptId: Long): Boolean = activeAttemptId == attemptId

    fun takeCleanup(attemptId: Long): Boolean {
        if (activeAttemptId != null || cleanupPendingAttemptId != attemptId) return false
        cleanupPendingAttemptId = null
        return true
    }
}

/**
 * Synchronized logging-only coordinator. It never controls startup behavior; it only routes
 * observations into one bounded [WppStartupTracker] attempt and emits one terminal summary.
 */
class WppDiagnosticsCoordinator(
    private val elapsedRealtimeMs: () -> Long,
    private val eventSink: (String) -> Unit,
    private val summarySink: (PhoneWppSummary) -> Unit,
    private val tracker: WppStartupTracker = WppStartupTracker(),
) {
    private var activeAttemptId: Long? = null

    @get:Synchronized
    val isActive: Boolean
        get() = activeAttemptId != null

    @get:Synchronized
    val attemptId: Long?
        get() = activeAttemptId

    @Synchronized
    fun selectedTargetConnected(existingBridgeActive: Boolean = false): Long? {
        val now = elapsedRealtimeMs()
        if (existingBridgeActive) {
            eventSink(
                "PHONE WPP EVENT attempt=none stage=${PhoneWppStage.TARGET_BT_CONNECTED.name} " +
                    "disposition=existing_bridge elapsed=$now",
            )
            return null
        }
        val wasActive = tracker.isActive
        val id = tracker.selectedCarConnected(now)
        activeAttemptId = id
        if (!wasActive) logEvent(id, "ATTEMPT_START", now)
        logEvent(id, PhoneWppStage.TARGET_BT_CONNECTED.name, now)
        return id
    }

    @Synchronized
    fun startOrJoin(trigger: WppIntegrationTrigger): Long {
        val input = when (trigger) {
            WppIntegrationTrigger.START -> WppStartInput.START
            WppIntegrationTrigger.PREWARM -> WppStartInput.PREWARM
            WppIntegrationTrigger.SELECTED_BT ->
                error("SELECTED_BT must use selectedTargetConnected()")
        }
        val now = elapsedRealtimeMs()
        val wasActive = tracker.isActive
        val id = tracker.startOrJoin(input, now)
        activeAttemptId = id
        if (!wasActive) logEvent(id, "ATTEMPT_START", now)
        eventSink("PHONE WPP EVENT attempt=$id stage=START_INPUT input=${input.name} elapsed=$now")
        return id
    }

    @Synchronized
    fun record(stage: PhoneWppStage) {
        if (stage == PhoneWppStage.BRIDGE_ESTABLISHED) {
            bridgeEstablished()
            return
        }
        if (stage == PhoneWppStage.BRIDGE_CLOSED) {
            // Close is post-startup and must arrive through bridgeClosed() with the
            // generation captured when that bridge was established.
            return
        }
        val id = activeAttemptId ?: return
        val now = elapsedRealtimeMs()
        tracker.record(stage, now)
        logEvent(id, stage.name, now)
    }

    /** Completes startup and returns the generation later close diagnostics must retain. */
    @Synchronized
    fun bridgeEstablished(): Long? {
        val id = activeAttemptId ?: return null
        val now = elapsedRealtimeMs()
        tracker.record(PhoneWppStage.BRIDGE_ESTABLISHED, now)
        logEvent(id, PhoneWppStage.BRIDGE_ESTABLISHED.name, now)
        finish(tracker.complete(now))
        return id
    }

    /** A bridge close is post-startup evidence and must never mutate the current attempt. */
    @Synchronized
    fun bridgeClosed(establishedAttemptId: Long?) {
        val now = elapsedRealtimeMs()
        val id = establishedAttemptId?.takeIf { it > 0L }?.toString() ?: "none"
        eventSink(
            "PHONE WPP EVENT attempt=$id stage=${PhoneWppStage.BRIDGE_CLOSED.name} " +
                "phase=post_startup elapsed=$now",
        )
    }

    @Synchronized
    fun associationOwner(owner: WppAssociationOwner) {
        val id = activeAttemptId ?: return
        val now = elapsedRealtimeMs()
        eventSink(
            "PHONE WPP EVENT attempt=$id stage=ASSOCIATION_OWNER " +
                "association_owner=${owner.logValue} elapsed=$now",
        )
    }

    @Synchronized
    fun timeout() {
        if (activeAttemptId == null) return
        finish(tracker.timeout(elapsedRealtimeMs()))
    }

    private fun finish(summary: PhoneWppSummary) {
        activeAttemptId = null
        summarySink(summary)
    }

    private fun logEvent(attemptId: Long, stage: String, elapsedMs: Long) {
        eventSink("PHONE WPP EVENT attempt=$attemptId stage=$stage elapsed=$elapsedMs")
    }
}
