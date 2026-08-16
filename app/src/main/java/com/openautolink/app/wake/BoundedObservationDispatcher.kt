package com.openautolink.app.wake

import java.util.ArrayDeque

/**
 * Moves observations from latency-sensitive callers to one deferred worker drain.
 *
 * [schedule] must defer the supplied drain runnable. [offer] only mutates the bounded in-memory
 * queue and requests a drain; observation callbacks are invoked exclusively by that drain.
 */
class BoundedObservationDispatcher<T>(
    private val capacity: Int,
    private val schedule: (() -> Unit) -> Unit,
    private val consume: (T) -> Unit,
    private val onDropped: (Int) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val lock = Any()
    private val queue = ArrayDeque<T>(capacity)
    private var drainScheduled = false
    private var droppedCount = 0

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun offer(observation: T): Boolean {
        var shouldSchedule = false
        val accepted = synchronized(lock) {
            if (queue.size >= capacity) {
                droppedCount += 1
                false
            } else {
                queue.addLast(observation)
                if (!drainScheduled) {
                    drainScheduled = true
                    shouldSchedule = true
                }
                true
            }
        }
        if (shouldSchedule) {
            try {
                schedule(::drain)
            } catch (_: Throwable) {
                synchronized(lock) {
                    droppedCount += queue.size
                    queue.clear()
                    drainScheduled = false
                }
                return false
            }
        }
        return accepted
    }

    private fun drain() {
        while (true) {
            val work = synchronized(lock) {
                when {
                    queue.isNotEmpty() -> DrainWork.Observation(queue.removeFirst())
                    droppedCount > 0 -> DrainWork.Dropped(droppedCount.also { droppedCount = 0 })
                    else -> {
                        drainScheduled = false
                        null
                    }
                }
            } ?: return

            when (work) {
                is DrainWork.Observation -> {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        consume(work.value as T)
                    } catch (error: Throwable) {
                        notifyFailure(error)
                    }
                }
                is DrainWork.Dropped -> {
                    try {
                        onDropped(work.count)
                    } catch (error: Throwable) {
                        notifyFailure(error)
                    }
                }
            }
        }
    }

    private fun notifyFailure(error: Throwable) {
        try {
            onFailure(error)
        } catch (_: Throwable) {
            // Diagnostics must never strand the drain or block later observations.
        }
    }

    private sealed interface DrainWork {
        data class Observation(val value: Any?) : DrainWork
        data class Dropped(val count: Int) : DrainWork
    }
}
