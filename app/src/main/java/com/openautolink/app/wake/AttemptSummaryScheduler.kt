package com.openautolink.app.wake

fun interface AttemptSummaryTimeout {
    fun cancel()
}

enum class AttemptSummaryOutcome {
    READY,
    TIMEOUT,
}

/**
 * Owns one terminal deadline per retained attempt. Attempt IDs, rather than a process-global
 * sampler, identify every callback so an old timeout can never summarize a newer attempt.
 */
class AttemptSummaryScheduler(
    private val timeoutMs: Long,
    private val schedule: (Long, Long, () -> Unit) -> AttemptSummaryTimeout,
    private val emit: (WakeSummary, AttemptSummaryOutcome) -> Unit,
) {
    private data class Pending(
        var summary: WakeSummary,
        val timeout: AttemptSummaryTimeout,
    )

    private val lock = Any()
    private val pendingByAttempt = linkedMapOf<Long, Pending>()
    private val completedAttemptIds = linkedSetOf<Long>()
    private var retainedIds = linkedSetOf<Long>()

    val retainedAttemptIds: Set<Long>
        get() = synchronized(lock) { retainedIds.toSet() }

    val activeTimeoutCount: Int
        get() = synchronized(lock) { pendingByAttempt.size }

    fun observe(current: WakeSummary, previous: WakeSummary?) {
        val summaries = listOfNotNull(previous, current).associateBy { it.attemptId }
        val evicted = mutableListOf<Pending>()
        synchronized(lock) {
            retainedIds = summaries.keys.toCollection(linkedSetOf())
            completedAttemptIds.retainAll(retainedIds)

            val staleIds = pendingByAttempt.keys.filter { it !in retainedIds }
            staleIds.forEach { staleId ->
                pendingByAttempt.remove(staleId)?.let { pending ->
                    completedAttemptIds += staleId
                    evicted += pending
                }
            }

            summaries.forEach { (attemptId, summary) ->
                val existing = pendingByAttempt[attemptId]
                if (existing != null) {
                    existing.summary = summary
                } else if (attemptId !in completedAttemptIds) {
                    val timeout = schedule(attemptId, timeoutMs) { timeout(attemptId) }
                    pendingByAttempt[attemptId] = Pending(summary, timeout)
                }
            }
        }
        evicted.forEach { pending ->
            pending.timeout.cancel()
            emit(pending.summary, AttemptSummaryOutcome.TIMEOUT)
        }
    }

    fun ready(summary: WakeSummary): Boolean =
        resolve(summary.attemptId, summary, AttemptSummaryOutcome.READY)

    private fun timeout(attemptId: Long) {
        resolve(attemptId, null, AttemptSummaryOutcome.TIMEOUT)
    }

    private fun resolve(
        attemptId: Long,
        suppliedSummary: WakeSummary?,
        outcome: AttemptSummaryOutcome,
    ): Boolean {
        val resolved = synchronized(lock) {
            val active = pendingByAttempt.remove(attemptId) ?: return false
            completedAttemptIds += attemptId
            active to (suppliedSummary ?: active.summary)
        }
        val (pending, summary) = resolved
        pending.timeout.cancel()
        emit(summary, outcome)
        return true
    }
}
