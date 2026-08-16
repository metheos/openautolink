package com.openautolink.app.wake

enum class GmIngressOutcome {
    ACCEPTED,
    DUPLICATE,
    WINDOW_LIMITED,
    HARD_CAPPED,
}

data class GmIngressResult(
    val allowed: Boolean,
    val outcome: GmIngressOutcome,
    val shouldLogSuppression: Boolean,
)

/** Stable, bounded keys for exported broadcasts that do not carry an integer state. */
object GmIngressDedupeKey {
    const val ACTION_ONLY = 0

    fun poweroffView(view: Boolean?, mute: Boolean?, fpi: Boolean?): Int =
        view.asTrit() + (mute.asTrit() * 3) + (fpi.asTrit() * 9)

    private fun Boolean?.asTrit(): Int = when (this) {
        null -> 0
        false -> 1
        true -> 2
    }
}

/** Pure, per-action availability guard for exported untrusted GM diagnostics. */
class GmBroadcastIngressLimiter(
    private val windowMs: Long = 1_000L,
    private val maxPerWindow: Int = 8,
    private val hardCapPerEpoch: Int = 64,
) {
    private data class ActionState(
        var epoch: Long,
        var acceptedTotal: Int = 0,
        val acceptedInWindow: ArrayDeque<Long> = ArrayDeque(),
        var hasLastRaw: Boolean = false,
        var lastRaw: Int? = null,
        var lastSeenAtMs: Long = Long.MIN_VALUE,
        val loggedSuppressions: MutableSet<GmIngressOutcome> = mutableSetOf(),
    )

    private val states = mutableMapOf<String, ActionState>()

    init {
        require(windowMs > 0L)
        require(maxPerWindow > 0)
        require(hardCapPerEpoch > 0)
    }

    @Synchronized
    fun evaluate(action: String, rawValue: Int?, elapsedMs: Long, epoch: Long): GmIngressResult {
        val state = states.getOrPut(action) { ActionState(epoch) }
        if (state.epoch != epoch) state.reset(epoch)

        val cutoff = elapsedMs - windowMs
        while (state.acceptedInWindow.firstOrNull()?.let { it <= cutoff } == true) {
            state.acceptedInWindow.removeFirst()
        }

        val duplicate =
            state.hasLastRaw && state.lastRaw == rawValue &&
                elapsedMs - state.lastSeenAtMs < windowMs
        state.hasLastRaw = true
        state.lastRaw = rawValue
        state.lastSeenAtMs = elapsedMs
        if (duplicate) {
            return state.suppressed(GmIngressOutcome.DUPLICATE)
        }
        if (state.acceptedTotal >= hardCapPerEpoch) {
            return state.suppressed(GmIngressOutcome.HARD_CAPPED)
        }
        if (state.acceptedInWindow.size >= maxPerWindow) {
            return state.suppressed(GmIngressOutcome.WINDOW_LIMITED)
        }

        state.acceptedTotal++
        state.acceptedInWindow.addLast(elapsedMs)
        return GmIngressResult(true, GmIngressOutcome.ACCEPTED, false)
    }

    fun handle(
        action: String,
        rawValue: Int?,
        elapsedMs: Long,
        epoch: Long,
        onAccepted: () -> Unit,
    ): GmIngressResult {
        val result = evaluate(action, rawValue, elapsedMs, epoch)
        if (result.allowed) onAccepted()
        return result
    }

    @Synchronized
    fun acceptedCount(action: String): Int = states[action]?.acceptedTotal ?: 0

    private fun ActionState.reset(newEpoch: Long) {
        epoch = newEpoch
        acceptedTotal = 0
        acceptedInWindow.clear()
        hasLastRaw = false
        lastRaw = null
        lastSeenAtMs = Long.MIN_VALUE
        loggedSuppressions.clear()
    }

    private fun ActionState.suppressed(outcome: GmIngressOutcome): GmIngressResult =
        GmIngressResult(
            allowed = false,
            outcome = outcome,
            shouldLogSuppression = loggedSuppressions.add(outcome),
        )
}
