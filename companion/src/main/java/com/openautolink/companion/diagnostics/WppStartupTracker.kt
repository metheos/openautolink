package com.openautolink.companion.diagnostics

enum class WppStartInput {
    START,
    PREWARM,
}

enum class PhoneWppStage {
    TARGET_BT_CONNECTED,
    SERVICE_READY,
    TCP_LISTENING,
    WARM_PROXY_READY,
    NETWORK_AVAILABLE,
    NETWORK_LOST,
    CAR_PROBE,
    CAR_SOCKET,
    AA_SOCKET,
    BRIDGE_ESTABLISHED,
    BRIDGE_CLOSED,
    TARGET_BT_DISCONNECTED,
    STOPPED,
}

data class PhoneWppEvent(
    val stage: PhoneWppStage,
    val elapsedMs: Long,
    val detail: String = "",
)

data class PhoneWppSummary(
    val attemptId: Long,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val startInputs: Set<WppStartInput>,
    val outcome: String,
    val missingStage: PhoneWppStage?,
    val timeline: List<PhoneWppEvent>,
)

/** Pure logging model for one phone-side WPP startup attempt. */
class WppStartupTracker(
    private val nextAttemptId: () -> Long = { DefaultAttemptIds.next() },
) {
    private var activeAttemptId: Long? = null
    private var lastAttemptId: Long? = null
    private var startedAtMs = 0L
    private val startInputs = linkedSetOf<WppStartInput>()
    private val events = mutableListOf<PhoneWppEvent>()
    private val observedStages = mutableSetOf<PhoneWppStage>()

    val isActive: Boolean
        get() = activeAttemptId != null

    fun startOrJoin(input: WppStartInput, elapsedMs: Long): Long {
        val id = ensureActiveAttempt(elapsedMs)
        startInputs += input
        return id
    }

    fun selectedCarConnected(elapsedMs: Long, detail: String = ""): Long {
        val id = ensureActiveAttempt(elapsedMs)
        record(PhoneWppStage.TARGET_BT_CONNECTED, elapsedMs, detail)
        return id
    }

    fun record(
        stage: PhoneWppStage,
        elapsedMs: Long,
        detail: String = "",
    ): PhoneWppSummary {
        ensureActiveAttempt(elapsedMs)
        observedStages += stage
        events += PhoneWppEvent(stage, elapsedMs, sanitizeDetail(detail))
        if (events.size > MAX_EVENTS) events.removeAt(0)
        return snapshot(endedAtMs = elapsedMs)
    }

    fun complete(elapsedMs: Long): PhoneWppSummary {
        val summary = snapshot(
            endedAtMs = elapsedMs,
            outcomeOverride = if (hasStage(PhoneWppStage.BRIDGE_ESTABLISHED)) {
                "connected"
            } else {
                "incomplete"
            },
        )
        activeAttemptId = null
        return summary
    }

    fun timeout(elapsedMs: Long): PhoneWppSummary {
        val summary = snapshot(endedAtMs = elapsedMs, outcomeOverride = "timeout")
        activeAttemptId = null
        return summary
    }

    private fun snapshot(
        endedAtMs: Long,
        outcomeOverride: String? = null,
    ): PhoneWppSummary = PhoneWppSummary(
        attemptId = checkNotNull(activeAttemptId) { "No WPP startup attempt is active" },
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        startInputs = startInputs.toSet(),
        outcome = outcomeOverride ?: outcome(),
        missingStage = REQUIRED_STARTUP_STAGES.firstOrNull { !hasStage(it) },
        timeline = events.sortedBy { it.elapsedMs },
    )

    private fun outcome(): String = when {
        hasStage(PhoneWppStage.BRIDGE_ESTABLISHED) -> "connected"
        hasStage(PhoneWppStage.AA_SOCKET) && !hasStage(PhoneWppStage.CAR_SOCKET) -> "waiting_for_car"
        hasStage(PhoneWppStage.CAR_SOCKET) && !hasStage(PhoneWppStage.AA_SOCKET) ->
            "waiting_for_android_auto"
        else -> "starting"
    }

    private fun hasStage(stage: PhoneWppStage): Boolean = stage in observedStages

    // Detail is intentionally fail-closed: stage and elapsed time are sufficient diagnostics,
    // while arbitrary producer text can contain identifiers, addresses, or credentials.
    private fun sanitizeDetail(@Suppress("UNUSED_PARAMETER") detail: String): String = ""

    private fun ensureActiveAttempt(elapsedMs: Long): Long {
        activeAttemptId?.let { return it }
        val supplied = nextAttemptId()
        require(supplied > 0L) { "WPP attempt ID must be positive: $supplied" }
        lastAttemptId?.let { previous ->
            require(supplied > previous) {
                "WPP attempt ID must increase: supplied=$supplied previous=$previous"
            }
        }
        events.clear()
        observedStages.clear()
        startInputs.clear()
        startedAtMs = elapsedMs
        val id = supplied
        lastAttemptId = id
        activeAttemptId = id
        return id
    }

    private object DefaultAttemptIds {
        private val counter = MonotonicAttemptIdCounter()

        fun next(): Long = counter.next()
    }

    companion object {
        const val MAX_EVENTS = 64
        const val MAX_DETAIL_LENGTH = 96

        private val REQUIRED_STARTUP_STAGES = listOf(
            PhoneWppStage.TARGET_BT_CONNECTED,
            PhoneWppStage.SERVICE_READY,
            PhoneWppStage.TCP_LISTENING,
            PhoneWppStage.WARM_PROXY_READY,
            PhoneWppStage.NETWORK_AVAILABLE,
            PhoneWppStage.CAR_PROBE,
            PhoneWppStage.CAR_SOCKET,
            PhoneWppStage.AA_SOCKET,
            PhoneWppStage.BRIDGE_ESTABLISHED,
        )
    }
}

internal class MonotonicAttemptIdCounter(initialValue: Long = 0L) {
    private var value = initialValue

    @Synchronized
    fun next(): Long {
        check(value < Long.MAX_VALUE) { "WPP attempt ID counter exhausted" }
        value += 1L
        return value
    }
}

object PhoneWppSummaryFormatter {
    const val MAX_LINE_LENGTH = 480

    fun format(summary: PhoneWppSummary): String {
        val prefix = buildString {
            append("PHONE WPP SUMMARY attempt=").append(summary.attemptId)
            append(" outcome=").append(summary.outcome)
            append(" missing=").append(summary.missingStage?.name ?: "none")
            append(" timeline=")
        }
        val timeline = summary.timeline.joinToString(">") { event ->
            buildString {
                append(event.stage.name).append('@').append(event.elapsedMs)
                if (event.detail.isNotBlank()) append('(').append(event.detail).append(')')
            }
        }
        val budget = (MAX_LINE_LENGTH - prefix.length).coerceAtLeast(0)
        val boundedTimeline = when {
            timeline.length <= budget -> timeline
            budget <= 1 -> "~".take(budget)
            else -> timeline.take(budget - 1) + "~"
        }
        return prefix + boundedTimeline
    }
}
