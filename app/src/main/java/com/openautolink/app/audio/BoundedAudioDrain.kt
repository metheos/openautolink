package com.openautolink.app.audio

import java.util.ArrayDeque

/**
 * Single-writer audio drain with a bounded newest-data queue.
 *
 * [offer] never blocks the protocol callback. When the pending-byte budget is
 * exceeded, the oldest queued chunks are discarded so a temporary slow
 * AudioTrack cannot turn into seconds of stale playback. [stopAndFlush]
 * invalidates the exact writer generation, clears pending chunks, waits for an
 * in-flight write to leave the sink, and only then flushes it.
 */
internal class BoundedAudioDrain(
    maxQueuedBytes: Int,
    private val threadName: String,
    private val onWrite: (ByteArray) -> Unit,
    private val onPause: () -> Unit,
    private val onFlush: () -> Unit,
    private val onWorkerStart: () -> Unit = {},
    private val onChunkClaimed: () -> Unit = {},
    private val onChunkFinished: () -> Unit = {},
) {
    data class OfferResult(
        val accepted: Boolean,
        val droppedChunks: Int,
        val droppedBytes: Int,
        val queuedBytes: Int,
    )

    data class StopResult(
        val generation: Long,
        val droppedChunks: Int,
        val droppedBytes: Int,
    )

    private data class Chunk(val generation: Long, val data: ByteArray)

    private val byteBudget = maxQueuedBytes.coerceAtLeast(1)
    private val monitor = Object()
    private val writeLock = Any()
    private val queue = ArrayDeque<Chunk>()

    private var queuedBytes = 0
    private var generation = 0L
    private var running = false
    private var worker: Thread? = null
    private var claimedChunk: Chunk? = null
    private var claimedWriting = false

    val pendingBytes: Int
        get() = synchronized(monitor) { queuedBytes }

    val pendingChunks: Int
        get() = synchronized(monitor) { queue.size }

    fun start(): Long = synchronized(monitor) {
        check(!running) { "audio drain is already running" }
        generation++
        val workerGeneration = generation
        running = true
        worker = Thread({ drainLoop(workerGeneration) }, threadName).apply {
            isDaemon = true
            start()
        }
        workerGeneration
    }

    fun offer(data: ByteArray): OfferResult = synchronized(monitor) {
        if (!running || data.isEmpty()) {
            return@synchronized OfferResult(
                accepted = false,
                droppedChunks = 0,
                droppedBytes = 0,
                queuedBytes = queuedBytes,
            )
        }

        var droppedChunks = 0
        var droppedBytes = 0
        var queuedData = data
        if (data.size > byteBudget) {
            val trimBytes = data.size - byteBudget
            queuedData = data.copyOfRange(trimBytes, data.size)
            droppedChunks++
            droppedBytes += trimBytes
        }
        while (queue.isNotEmpty() && queuedBytes + queuedData.size > byteBudget) {
            val stale = queue.removeFirst()
            queuedBytes -= stale.data.size
            droppedChunks++
            droppedBytes += stale.data.size
        }

        queue.addLast(Chunk(generation, queuedData))
        queuedBytes += queuedData.size
        monitor.notifyAll()
        OfferResult(
            accepted = true,
            droppedChunks = droppedChunks,
            droppedBytes = droppedBytes,
            queuedBytes = queuedBytes,
        )
    }

    fun stopAndFlush(): StopResult {
        val result: StopResult
        synchronized(monitor) {
            val stoppedGeneration = generation
            running = false
            generation++
            var droppedBytes = 0
            for (chunk in queue) droppedBytes += chunk.data.size
            val claimedDrop = claimedChunk?.takeIf {
                !claimedWriting && it.generation == stoppedGeneration
            }
            result = StopResult(
                generation = stoppedGeneration,
                droppedChunks = queue.size + if (claimedDrop != null) 1 else 0,
                droppedBytes = droppedBytes + (claimedDrop?.data?.size ?: 0),
            )
            queue.clear()
            queuedBytes = 0
            worker = null
            monitor.notifyAll()
        }

        // Pause first. Android's streaming AudioTrack can otherwise leave a
        // blocking write waiting for playback capacity while stop waits for the
        // same write to release this lock.
        onPause()
        // Do not interrupt the worker here: it may already be inside the sink.
        // notifyAll() above wakes a worker waiting for queued data.
        // If a write already entered the sink, let it return before flushing.
        // If it has not entered yet, its generation check below will reject it.
        synchronized(writeLock) {
            onFlush()
        }
        return result
    }

    private fun drainLoop(workerGeneration: Long) {
        onWorkerStart()
        while (true) {
            val chunk = synchronized(monitor) {
                while (running && generation == workerGeneration && queue.isEmpty()) {
                    try {
                        monitor.wait()
                    } catch (_: InterruptedException) {
                        // Re-check generation/running state below.
                    }
                }
                if (!running || generation != workerGeneration) return
                queue.removeFirst().also {
                    queuedBytes -= it.data.size
                    claimedChunk = it
                    claimedWriting = false
                }
            }

            try {
                onChunkClaimed()
                synchronized(writeLock) {
                    val current = synchronized(monitor) {
                        val valid = running && generation == workerGeneration &&
                            chunk.generation == workerGeneration
                        if (valid) claimedWriting = true
                        valid
                    }
                    if (current) onWrite(chunk.data)
                }
            } finally {
                synchronized(monitor) {
                    if (claimedChunk === chunk) {
                        claimedChunk = null
                        claimedWriting = false
                    }
                }
                onChunkFinished()
            }
        }
    }
}
