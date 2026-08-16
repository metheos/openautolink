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

data class WakeAttemptWindow(
    val attemptId: Long,
    val startsAfterMs: Long?,
    val endsAtMs: Long?,
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
    val sessionReadySource: String?,
    val surfaceReadyAtMs: Long?,
    val apAbsentToPresentAtMs: Long?,
    val timeline: List<WakeEvent>,
)

internal val OBSERVATIONAL_GM_DETAILS = setOf(
    "raw=1,name=ANIMATION_INIT",
    "raw=2,name=HMI_INIT",
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

    fun retainedAttemptWindows(): List<WakeAttemptWindow> = listOfNotNull(
        previousAttempt?.let { previous ->
            WakeAttemptWindow(previous.id, previous.startsAfterMs, currentAttempt?.startsAfterMs)
        },
        currentAttempt?.let { current ->
            WakeAttemptWindow(current.id, current.startsAfterMs, current.lastIgnitionOffMs())
        },
    )

    fun record(event: WakeEvent): WakeSummary {
        val existing = currentAttempt
        if (existing == null) {
            currentAttempt = Attempt(nextAttemptId())
        } else if (existing.startsAfterMs != null && event.elapsedMs <= existing.startsAfterMs) {
            previousAttempt
                ?.takeIf { it.startsAfterMs == null || event.elapsedMs > it.startsAfterMs }
                ?.let { previous ->
                    previous.add(event)
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
            add(event)
            toSummary()
        }
    }

    private fun Attempt.shouldRollOverFor(event: WakeEvent): Boolean {
        val lastIgnitionOff = lastIgnitionOffMs() ?: return false
        return event.elapsedMs > lastIgnitionOff &&
            event.signal in ATTEMPT_START_SIGNALS &&
            (event.signal != WakeSignal.GM_SYSTEM_STATE || event.detail in OBSERVATIONAL_GM_DETAILS)
    }

    private fun Attempt.lastIgnitionOffMs(): Long? = events
        .asSequence()
        .filter { it.signal == WakeSignal.IGNITION_OFF }
        .maxOfOrNull { it.elapsedMs }

    private fun Attempt.add(event: WakeEvent) {
        events += event
        if (events.size <= MAX_TIMELINE_EVENTS) return

        val ordered = events.sortedWith(EVENT_ORDER)
        val protected = linkedSetOf<WakeEvent>()
        WakeSignal.entries.forEach { signal ->
            ordered.firstOrNull { it.signal == signal }?.let(protected::add)
        }
        ordered.firstOrNull {
            it.signal == WakeSignal.GM_SYSTEM_STATE &&
                it.detail in OBSERVATIONAL_GM_DETAILS
        }?.let(protected::add)
        ordered.firstTrueApEdge()?.let(protected::add)
        LATEST_EDGE_SIGNALS.forEach { signal ->
            ordered.lastOrNull { it.signal == signal }?.let(protected::add)
        }
        ordered.asReversed().forEach { candidate ->
            if (protected.size < MAX_TIMELINE_EVENTS) protected += candidate
        }
        events.clear()
        events += protected.sortedWith(EVENT_ORDER)
    }

    private fun Attempt.toSummary(): WakeSummary {
        val timeline = events.sortedWith(EVENT_ORDER)
        val trigger = timeline.trigger()
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
            trigger = trigger,
            gmState = timeline.gmStateFor(trigger),
            btReadyAtMs = timeline.firstTime(WakeSignal.BLUETOOTH_ON),
            apReadyAtMs = timeline.firstTime(WakeSignal.AP_PRESENT),
            ignitionOnAtMs = timeline.firstTime(WakeSignal.IGNITION_ON),
            activityStartedAtMs = timeline.firstTime(WakeSignal.ACTIVITY_START),
            sessionReadyAtMs = timeline.firstTime(WakeSignal.SESSION_READY),
            sessionReadySource = timeline.firstSource(WakeSignal.SESSION_READY),
            surfaceReadyAtMs = timeline.firstTime(WakeSignal.SURFACE_READY),
            apAbsentToPresentAtMs = apEdgeAtMs,
            timeline = timeline,
        )
    }

    private fun List<WakeEvent>.trigger(): WakeSignal {
        firstOrNull {
            it.signal == WakeSignal.GM_SYSTEM_STATE &&
                it.detail in OBSERVATIONAL_GM_DETAILS
        }?.let { return it.signal }
        firstOrNull { it.signal == WakeSignal.IGNITION_ON }?.let { return it.signal }
        firstOrNull { it.signal == WakeSignal.PROCESS_START }?.let { return it.signal }
        return first().signal
    }

    private fun List<WakeEvent>.firstTime(signal: WakeSignal): Long? =
        firstOrNull { it.signal == signal }?.elapsedMs

    private fun List<WakeEvent>.firstTrueApEdge(): WakeEvent? {
        var apAbsent = false
        for (event in this) {
            when (event.signal) {
                WakeSignal.AP_ABSENT -> apAbsent = true
                WakeSignal.AP_PRESENT -> {
                    if (apAbsent) return event
                    apAbsent = false
                }
                else -> Unit
            }
        }
        return null
    }

    private fun List<WakeEvent>.firstDetail(signal: WakeSignal): String? =
        firstOrNull { it.signal == signal }?.detail?.takeIf { it.isNotBlank() }

    private fun List<WakeEvent>.gmStateFor(trigger: WakeSignal): String? =
        if (trigger == WakeSignal.GM_SYSTEM_STATE) {
            firstOrNull {
                it.signal == WakeSignal.GM_SYSTEM_STATE &&
                    it.detail in OBSERVATIONAL_GM_DETAILS
            }?.detail
        } else {
            firstDetail(WakeSignal.GM_SYSTEM_STATE)
        }

    private fun List<WakeEvent>.firstSource(signal: WakeSignal): String? =
        firstOrNull { it.signal == signal }
            ?.detail
            ?.split(',')
            ?.firstOrNull { it.startsWith("source=") }
            ?.substringAfter("source=")
            ?.takeIf { it.isNotBlank() }

    companion object {
        const val MAX_TIMELINE_EVENTS = 64

        private val EVENT_ORDER = compareBy<WakeEvent>(
            { it.elapsedMs },
            { it.signal.ordinal },
            { it.detail },
        )

        private val LATEST_EDGE_SIGNALS = setOf(
            WakeSignal.GM_SYSTEM_STATE,
            WakeSignal.BLUETOOTH_OFF,
            WakeSignal.BLUETOOTH_ON,
            WakeSignal.AP_ABSENT,
            WakeSignal.AP_PRESENT,
            WakeSignal.IGNITION_OFF,
            WakeSignal.IGNITION_ON,
            WakeSignal.IGNITION_START,
        )

        private val ATTEMPT_START_SIGNALS = WakeSignal.entries.toSet() - setOf(
            WakeSignal.BLUETOOTH_OFF,
            WakeSignal.AP_ABSENT,
            WakeSignal.IGNITION_OFF,
        )
    }
}

object WakeSummaryFormatter {
    const val MAX_DIAGNOSTIC_LINE_LENGTH = 480

    fun format(summary: WakeSummary): String =
        formatFixedFields(summary) + " timeline=" + formatTimeline(summary)

    fun formatForDiagnosticLog(
        summary: WakeSummary,
        gmEvidenceFields: String,
        outcome: String,
        missing: String,
    ): String {
        val terminalFields =
            " ${gmEvidenceFields.singleLine().take(128)}" +
                " outcome=${outcome.singleLine().take(32)}" +
                " missing=${missing.singleLine().take(96)}"
        var prefix = formatFixedFields(summary) + terminalFields + " timeline="
        if (prefix.length > MAX_DIAGNOSTIC_LINE_LENGTH) {
            prefix = formatFixedFields(summary, compact = true) + terminalFields + " timeline="
        }
        val timelineBudget = (MAX_DIAGNOSTIC_LINE_LENGTH - prefix.length).coerceAtLeast(0)
        val timeline = formatTimeline(summary)
        val boundedTimeline = when {
            timeline.length <= timelineBudget -> timeline
            timelineBudget == 0 -> ""
            else -> timeline.take(timelineBudget - 1) + "~"
        }
        return (prefix + boundedTimeline).take(MAX_DIAGNOSTIC_LINE_LENGTH)
    }

    private fun formatFixedFields(summary: WakeSummary, compact: Boolean = false): String =
        buildString {
            append("WAKE SUMMARY")
            append(" attempt=").append(summary.attemptId)
            append(" trigger=").append(summary.trigger.name)
            append(" gm=").append(summary.gmState.asField().let { if (compact) it.take(32) else it })
            append(" bt=").append(summary.btReadyAtMs.stageField(compact))
            append(" ap=").append(summary.apReadyAtMs.stageField(compact))
            append(" apEdge=").append(summary.apAbsentToPresentAtMs.stageField(compact))
            append(" ignition=").append(summary.ignitionOnAtMs.stageField(compact))
            append(" activity=").append(summary.activityStartedAtMs.stageField(compact))
            append(" session=").append(summary.sessionReadyAtMs.stageField(compact))
            append(" sessionSource=").append(summary.sessionReadySource.asField())
            append(" surface=").append(summary.surfaceReadyAtMs.stageField(compact))
        }

    private fun formatTimeline(summary: WakeSummary): String =
        summary.timeline.joinToString(">") { event ->
            buildString {
                append(event.signal.name).append('@').append(event.elapsedMs)
                if (event.detail.isNotBlank()) {
                    append('(').append(event.detail.singleLine()).append(')')
                }
            }
        }

    private fun Long?.stageField(compact: Boolean): String = when {
        this == null -> "-"
        compact -> "+"
        else -> toString()
    }

    private fun Any?.asField(): String = this?.toString()?.singleLine() ?: "-"

    private fun String.singleLine(): String = trim().replace(Regex("\\s+"), "_")
}
