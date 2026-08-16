package com.openautolink.app.wake

enum class WakeSignal {
    PROCESS_START,
    GM_SYSTEM_STATE,
    BLUETOOTH_OFF,
    BLUETOOTH_ON,
    AP_ABSENT,
    AP_PRESENT,
    IGNITION_OFF,
    IGNITION_ON,
    IGNITION_START,
    ACTIVITY_START,
    ACTIVITY_RESUME,
    SESSION_READY,
    SURFACE_READY,
    SDP_PUBLISHED,
    PHONE_DIALBACK,
}

data class WakeEvent(
    val signal: WakeSignal,
    val elapsedMs: Long,
    val detail: String = "",
)

data class WakeSummary(
    val attemptId: Long,
    val trigger: WakeSignal,
    val gmState: String?,
    val btReadyAtMs: Long?,
    val apReadyAtMs: Long?,
    val ignitionOnAtMs: Long?,
    val activityStartedAtMs: Long?,
    val sessionReadyAtMs: Long?,
    val surfaceReadyAtMs: Long?,
    val apAbsentToPresentAtMs: Long?,
    val timeline: List<WakeEvent>,
)

/**
 * Reduces logging signals into one bounded wake timeline. It intentionally has no behavior hooks.
 */
class WakeAttemptReducer(
    private val nextAttemptId: () -> Long,
) {
    private data class Attempt(
        val id: Long,
        val startsAfterMs: Long? = null,
        val events: MutableList<WakeEvent> = mutableListOf(),
    )

    private var currentAttempt: Attempt? = null
    private var previousAttempt: Attempt? = null

    var previousSummary: WakeSummary? = null
        private set

    val currentSummary: WakeSummary?
        get() = currentAttempt?.toSummary()

    fun record(event: WakeEvent): WakeSummary {
        val existing = currentAttempt
        if (existing == null) {
            currentAttempt = Attempt(nextAttemptId())
        } else if (existing.startsAfterMs != null && event.elapsedMs <= existing.startsAfterMs) {
            previousAttempt
                ?.takeIf { it.startsAfterMs == null || event.elapsedMs > it.startsAfterMs }
                ?.let { previous ->
                    previous.events += event
                    previousSummary = previous.toSummary()
                }
            return existing.toSummary()
        } else if (existing.shouldRollOverFor(event)) {
            val boundaryMs = existing.lastIgnitionOffMs()!!
            previousAttempt = Attempt(
                id = existing.id,
                startsAfterMs = existing.startsAfterMs,
                events = existing.events.filterTo(mutableListOf()) { it.elapsedMs <= boundaryMs },
            )
            previousSummary = previousAttempt!!.toSummary()
            currentAttempt = Attempt(
                id = nextAttemptId(),
                startsAfterMs = boundaryMs,
                events = existing.events.filterTo(mutableListOf()) { it.elapsedMs > boundaryMs },
            )
        }

        return currentAttempt!!.run {
            events += event
            toSummary()
        }
    }

    private fun Attempt.shouldRollOverFor(event: WakeEvent): Boolean {
        val lastIgnitionOff = lastIgnitionOffMs() ?: return false
        return event.elapsedMs > lastIgnitionOff && event.signal in ATTEMPT_START_SIGNALS
    }

    private fun Attempt.lastIgnitionOffMs(): Long? = events
        .asSequence()
        .filter { it.signal == WakeSignal.IGNITION_OFF }
        .maxOfOrNull { it.elapsedMs }

    private fun Attempt.toSummary(): WakeSummary {
        val timeline = events.sortedWith(
            compareBy<WakeEvent>({ it.elapsedMs }, { it.signal.ordinal }, { it.detail })
        )
        var apAbsent = false
        var apEdgeAtMs: Long? = null
        timeline.forEach { event ->
            when (event.signal) {
                WakeSignal.AP_ABSENT -> apAbsent = true
                WakeSignal.AP_PRESENT -> {
                    if (apAbsent && apEdgeAtMs == null) {
                        apEdgeAtMs = event.elapsedMs
                    }
                    apAbsent = false
                }
                else -> Unit
            }
        }

        return WakeSummary(
            attemptId = id,
            trigger = timeline.trigger(),
            gmState = timeline.firstDetail(WakeSignal.GM_SYSTEM_STATE),
            btReadyAtMs = timeline.firstTime(WakeSignal.BLUETOOTH_ON),
            apReadyAtMs = timeline.firstTime(WakeSignal.AP_PRESENT),
            ignitionOnAtMs = timeline.firstTime(WakeSignal.IGNITION_ON),
            activityStartedAtMs = timeline.firstTime(WakeSignal.ACTIVITY_START),
            sessionReadyAtMs = timeline.firstTime(WakeSignal.SESSION_READY),
            surfaceReadyAtMs = timeline.firstTime(WakeSignal.SURFACE_READY),
            apAbsentToPresentAtMs = apEdgeAtMs,
            timeline = timeline,
        )
    }

    private fun List<WakeEvent>.trigger(): WakeSignal {
        firstOrNull {
            it.signal == WakeSignal.GM_SYSTEM_STATE &&
                it.detail in OBSERVATIONAL_GM_STATES
        }?.let { return it.signal }
        firstOrNull { it.signal == WakeSignal.IGNITION_ON }?.let { return it.signal }
        firstOrNull { it.signal == WakeSignal.PROCESS_START }?.let { return it.signal }
        return first().signal
    }

    private fun List<WakeEvent>.firstTime(signal: WakeSignal): Long? =
        firstOrNull { it.signal == signal }?.elapsedMs

    private fun List<WakeEvent>.firstDetail(signal: WakeSignal): String? =
        firstOrNull { it.signal == signal }?.detail?.takeIf { it.isNotBlank() }

    private companion object {
        val OBSERVATIONAL_GM_STATES = setOf("ANIMATION_INIT", "HMI_INIT")

        val ATTEMPT_START_SIGNALS = WakeSignal.entries.toSet() - setOf(
            WakeSignal.BLUETOOTH_OFF,
            WakeSignal.AP_ABSENT,
            WakeSignal.IGNITION_OFF,
        )
    }
}

object WakeSummaryFormatter {
    fun format(summary: WakeSummary): String = buildString {
        append("WAKE SUMMARY")
        append(" attempt=").append(summary.attemptId)
        append(" trigger=").append(summary.trigger.name)
        append(" gm=").append(summary.gmState.asField())
        append(" bt=").append(summary.btReadyAtMs.asField())
        append(" ap=").append(summary.apReadyAtMs.asField())
        append(" apEdge=").append(summary.apAbsentToPresentAtMs.asField())
        append(" ignition=").append(summary.ignitionOnAtMs.asField())
        append(" activity=").append(summary.activityStartedAtMs.asField())
        append(" session=").append(summary.sessionReadyAtMs.asField())
        append(" surface=").append(summary.surfaceReadyAtMs.asField())
        append(" timeline=")
        append(summary.timeline.joinToString(">") { event ->
            buildString {
                append(event.signal.name).append('@').append(event.elapsedMs)
                if (event.detail.isNotBlank()) {
                    append('(').append(event.detail.singleLine()).append(')')
                }
            }
        })
    }

    private fun Any?.asField(): String = this?.toString()?.singleLine() ?: "-"

    private fun String.singleLine(): String = trim().replace(Regex("\\s+"), "_")
}
