package com.openautolink.app.wake

/** A typed, privacy-safe observation produced by [PreWakeSignalPolicy]. */
data class PreWakeObservation(
    val kind: PreWakeObservationKind,
    val detail: String,
    /** GM broadcasts are evidence for diagnostics only, never authorization. */
    val hintOnly: Boolean = false,
)

enum class PreWakeObservationKind {
    GM_SYSTEM_STATE,
    GM_POWER_MODE,
    BLUETOOTH_STATE,
    ACCESS_POINT,
    IGNITION,
    ACTIVITY,
    SESSION,
    SURFACE,
    SDP,
    PHONE_DIALBACK,
}

enum class PreWakeActivityCallback { START, RESUME, PAUSE, STOP }

data class PreWakeSamplingPlan(
    val intervalMs: Long,
    val durationMs: Long,
) {
    init {
        require(intervalMs > 0L)
        require(durationMs >= intervalMs)
    }

    val maximumSamples: Int = ((durationMs + intervalMs - 1L) / intervalMs).toInt()
    val isBounded: Boolean = maximumSamples > 0
}

data class PreWakePolicyDecision(
    val observation: PreWakeObservation,
    val event: WakeEvent?,
    val sampling: PreWakeSamplingPlan? = null,
    /** True only for the truthful SESSION_READY callback, never for environmental hints. */
    val impliesSessionReadiness: Boolean = false,
    /** This policy is diagnostic-only. No input authorizes projection behavior. */
    val authorizesBehavior: Boolean = false,
)

/**
 * Pure mapping from framework observations to the Task 2 wake vocabulary.
 *
 * This class deliberately owns no threads and performs no I/O. It cannot publish SDP, dial,
 * launch projection, request a network, or otherwise authorize behavior. The process monitor
 * serializes calls into the mutable [WakeAttemptReducer].
 */
class PreWakeSignalPolicy {

    fun gmSystemState(rawState: Int, elapsedMs: Long): PreWakePolicyDecision {
        val name = GM_SYSTEM_STATES[rawState] ?: "UNKNOWN"
        return decision(
            kind = PreWakeObservationKind.GM_SYSTEM_STATE,
            detail = "raw=$rawState,name=$name",
            event = WakeEvent(
                WakeSignal.GM_SYSTEM_STATE,
                elapsedMs,
                detail = "raw=$rawState,name=$name",
            ),
            wakeCandidate = rawState in GM_SYSTEM_STATES,
            hintOnly = true,
        )
    }

    fun gmPowerMode(rawMode: Int, elapsedMs: Long): PreWakePolicyDecision = decision(
        kind = PreWakeObservationKind.GM_POWER_MODE,
        detail = "raw=$rawMode",
        // Task 2 intentionally has no readiness field for this spoofable broadcast.
        event = null,
        wakeCandidate = rawMode in GM_WAKE_POWER_MODES,
        hintOnly = true,
    )

    fun bluetoothState(rawState: Int, elapsedMs: Long): PreWakePolicyDecision {
        val signal = when (rawState) {
            BLUETOOTH_STATE_ON -> WakeSignal.BLUETOOTH_ON
            BLUETOOTH_STATE_OFF -> WakeSignal.BLUETOOTH_OFF
            else -> null
        }
        return decision(
            kind = PreWakeObservationKind.BLUETOOTH_STATE,
            detail = "raw=$rawState",
            event = signal?.let { WakeEvent(it, elapsedMs, "raw=$rawState") },
            wakeCandidate = rawState == BLUETOOTH_STATE_ON,
        )
    }

    fun accessPoint(
        interfaceName: String,
        localIpv4: String?,
        elapsedMs: Long,
    ): PreWakePolicyDecision {
        val safeInterface = safeField(interfaceName)
        val safeIp = localIpv4?.let(::safeField)?.takeIf { it.isNotBlank() }
        val detail = if (safeIp == null) {
            "interface=$safeInterface,ip=-"
        } else {
            "interface=$safeInterface,ip=$safeIp"
        }
        return decision(
            kind = PreWakeObservationKind.ACCESS_POINT,
            detail = detail,
            event = WakeEvent(
                if (safeIp == null) WakeSignal.AP_ABSENT else WakeSignal.AP_PRESENT,
                elapsedMs,
                detail,
            ),
            wakeCandidate = safeIp != null,
        )
    }

    fun ignitionState(rawState: Int, elapsedMs: Long): PreWakePolicyDecision {
        val signal = when (rawState) {
            2 -> WakeSignal.IGNITION_OFF
            4 -> WakeSignal.IGNITION_ON
            5 -> WakeSignal.IGNITION_START
            else -> null
        }
        return decision(
            kind = PreWakeObservationKind.IGNITION,
            detail = "raw=$rawState",
            event = signal?.let { WakeEvent(it, elapsedMs, "raw=$rawState") },
            wakeCandidate = rawState == 4 || rawState == 5,
        )
    }

    fun activity(
        callback: PreWakeActivityCallback,
        elapsedMs: Long,
    ): PreWakePolicyDecision {
        val signal = when (callback) {
            PreWakeActivityCallback.START -> WakeSignal.ACTIVITY_START
            PreWakeActivityCallback.RESUME -> WakeSignal.ACTIVITY_RESUME
            PreWakeActivityCallback.PAUSE,
            PreWakeActivityCallback.STOP -> null
        }
        return decision(
            kind = PreWakeObservationKind.ACTIVITY,
            detail = "callback=${callback.name}",
            event = signal?.let { WakeEvent(it, elapsedMs, callback.name) },
            wakeCandidate = signal != null,
        )
    }

    fun sessionReady(elapsedMs: Long): PreWakePolicyDecision = decision(
        kind = PreWakeObservationKind.SESSION,
        detail = "ready=true",
        event = WakeEvent(WakeSignal.SESSION_READY, elapsedMs),
        impliesSessionReadiness = true,
    )

    fun surfaceReady(width: Int, height: Int, elapsedMs: Long): PreWakePolicyDecision = decision(
        kind = PreWakeObservationKind.SURFACE,
        detail = "valid=true,size=${width}x$height",
        event = WakeEvent(WakeSignal.SURFACE_READY, elapsedMs, "${width}x$height"),
    )

    fun sdpPublished(elapsedMs: Long): PreWakePolicyDecision = decision(
        kind = PreWakeObservationKind.SDP,
        detail = "published=true",
        event = WakeEvent(WakeSignal.SDP_PUBLISHED, elapsedMs),
    )

    fun phoneDialback(elapsedMs: Long): PreWakePolicyDecision = decision(
        kind = PreWakeObservationKind.PHONE_DIALBACK,
        detail = "accepted=true",
        event = WakeEvent(WakeSignal.PHONE_DIALBACK, elapsedMs),
    )

    private fun decision(
        kind: PreWakeObservationKind,
        detail: String,
        event: WakeEvent?,
        wakeCandidate: Boolean = false,
        hintOnly: Boolean = false,
        impliesSessionReadiness: Boolean = false,
    ) = PreWakePolicyDecision(
        observation = PreWakeObservation(kind, safeDetail(detail), hintOnly),
        event = event?.copy(detail = safeDetail(event.detail)),
        sampling = if (wakeCandidate) DEFAULT_SAMPLING_PLAN else null,
        impliesSessionReadiness = impliesSessionReadiness,
        authorizesBehavior = false,
    )

    private fun safeDetail(value: String): String = value
        .replace(UNSAFE_DETAIL_CHARS, "_")
        .take(MAX_DETAIL_LENGTH)

    private fun safeField(value: String): String = value
        .trim()
        .replace(UNSAFE_FIELD_CHARS, "_")
        .take(MAX_FIELD_LENGTH)

    companion object {
        const val BLUETOOTH_STATE_OFF = 10
        const val BLUETOOTH_STATE_ON = 12
        private const val MAX_FIELD_LENGTH = 64
        private const val MAX_DETAIL_LENGTH = 160
        private val UNSAFE_FIELD_CHARS = Regex("[^A-Za-z0-9._:-]")
        private val UNSAFE_DETAIL_CHARS = Regex("[\\p{Cc}>()]")

        val DEFAULT_SAMPLING_PLAN = PreWakeSamplingPlan(
            intervalMs = 250L,
            durationMs = 15_000L,
        )

        private val GM_SYSTEM_STATES = mapOf(
            1 to "ANIMATION_INIT",
            2 to "HMI_INIT",
            3 to "HMI_INACTIVE",
            4 to "START",
            5 to "RUN",
            6 to "PROPULSION",
            7 to "ACCESSORY",
            8 to "LOCAL_INFOTAINMENT",
        )
        private val GM_WAKE_POWER_MODES = setOf(2, 5) // ON/STARTUP and legacy RESUME
    }
}
