package com.openautolink.companion.diagnostics

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Android runtime facade for the pure, logging-only WPP diagnostics coordinator. */
object PhoneWppDiagnostics {
    const val MAX_ATTEMPT_DURATION_MS = 120_000L

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var timeoutRunnable: Runnable? = null
    private var timeoutAttemptId: Long? = null
    private var attemptFinishedListener: (() -> Unit)? = null
    private val lifecycleGuard = WppAttemptLifecycleGuard()
    private val coordinator = WppDiagnosticsCoordinator(
        elapsedRealtimeMs = SystemClock::elapsedRealtime,
        eventSink = { CompanionLog.i(TAG, it) },
        summarySink = { summary ->
            CompanionLog.i(TAG, PhoneWppSummaryFormatter.format(summary))
            finishAttemptLifecycle(summary.attemptId)
        },
    )

    @Synchronized
    fun selectedTargetConnected(existingBridgeActive: Boolean = false): Long {
        return try {
            val wasActive = coordinator.isActive
            val id = coordinator.selectedTargetConnected(existingBridgeActive) ?: return 0L
            if (!wasActive) armTimeout(id)
            id
        } catch (e: Exception) {
            CompanionLog.w(TAG, "Passive WPP diagnostics unavailable: ${e.message}")
            0L
        }
    }

    @Synchronized
    fun startOrJoin(trigger: WppIntegrationTrigger): Long {
        return try {
            val wasActive = coordinator.isActive
            val id = coordinator.startOrJoin(trigger)
            if (!wasActive) armTimeout(id)
            id
        } catch (e: Exception) {
            CompanionLog.w(TAG, "Passive WPP diagnostics unavailable: ${e.message}")
            0L
        }
    }

    @Synchronized
    fun record(stage: PhoneWppStage) {
        try {
            coordinator.record(stage)
        } catch (e: Exception) {
            CompanionLog.w(TAG, "Passive WPP event dropped: ${e.message}")
        }
    }

    @Synchronized
    fun bridgeEstablished(): Long? {
        return try {
            coordinator.bridgeEstablished()
        } catch (e: Exception) {
            CompanionLog.w(TAG, "Passive WPP bridge event dropped: ${e.message}")
            null
        }
    }

    @Synchronized
    fun bridgeClosed(establishedAttemptId: Long?) {
        try {
            coordinator.bridgeClosed(establishedAttemptId)
        } catch (e: Exception) {
            CompanionLog.w(TAG, "Passive WPP bridge-close event dropped: ${e.message}")
        }
    }

    @Synchronized
    fun associationOwner(owner: WppAssociationOwner) {
        try {
            coordinator.associationOwner(owner)
        } catch (e: Exception) {
            CompanionLog.w(TAG, "Passive WPP association event dropped: ${e.message}")
        }
    }

    @Synchronized
    fun timeout() {
        try {
            coordinator.attemptId?.let(::timeout)
        } catch (e: Exception) {
            CompanionLog.w(TAG, "Passive WPP timeout event dropped: ${e.message}")
        }
    }

    @Synchronized
    fun setAttemptFinishedListener(listener: (() -> Unit)?) {
        attemptFinishedListener = listener
    }

    @get:Synchronized
    val isActive: Boolean
        get() = coordinator.isActive

    private fun armTimeout(attemptId: Long) {
        lifecycleGuard.started(attemptId)
        timeoutRunnable?.let(handler::removeCallbacks)
        val timeout = Runnable { timeout(attemptId) }
        timeoutRunnable = timeout
        timeoutAttemptId = attemptId
        handler.postDelayed(timeout, MAX_ATTEMPT_DURATION_MS)
    }

    @Synchronized
    private fun timeout(attemptId: Long) {
        if (!lifecycleGuard.ownsActiveAttempt(attemptId) || coordinator.attemptId != attemptId) return
        coordinator.timeout()
    }

    @Synchronized
    private fun finishAttemptLifecycle(attemptId: Long) {
        lifecycleGuard.completed(attemptId)
        if (timeoutAttemptId == attemptId) {
            timeoutRunnable?.let(handler::removeCallbacks)
            timeoutRunnable = null
            timeoutAttemptId = null
        }
        handler.post { notifyAttemptFinished(attemptId) }
    }

    private fun notifyAttemptFinished(attemptId: Long) {
        val listener = synchronized(this) {
            if (!lifecycleGuard.takeCleanup(attemptId)) return
            attemptFinishedListener
        }
        listener?.invoke()
    }

    private const val TAG = "OAL_WppStartup"
}
