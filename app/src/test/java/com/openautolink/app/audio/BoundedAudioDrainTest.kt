package com.openautolink.app.audio

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedAudioDrainTest {

    @Test
    fun `empty chunks are rejected without consuming queue entries`() {
        val drain = BoundedAudioDrain(
            maxQueuedBytes = 8,
            threadName = "bounded-audio-empty-test",
            onWrite = {},
            onPause = {},
            onFlush = {},
        )

        drain.start()
        repeat(1_000) {
            assertFalse(drain.offer(ByteArray(0)).accepted)
        }
        assertEquals(0, drain.pendingBytes)
        assertEquals(0, drain.pendingChunks)
        drain.stopAndFlush()
    }

    @Test
    fun `overflow keeps newest queued audio instead of replaying stale chunks`() {
        val firstWriteEntered = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val allExpectedWrites = CountDownLatch(3)
        val writes = Collections.synchronizedList(mutableListOf<Int>())
        val drain = BoundedAudioDrain(
            maxQueuedBytes = 8,
            threadName = "bounded-audio-overflow-test",
            onWrite = { data ->
                writes += data.first().toInt()
                allExpectedWrites.countDown()
                if (data.first() == 1.toByte()) {
                    firstWriteEntered.countDown()
                    assertTrue(releaseFirstWrite.await(2, TimeUnit.SECONDS))
                }
            },
            onPause = {},
            onFlush = {},
        )

        drain.start()
        drain.offer(byteArrayOf(1, 1, 1, 1))
        assertTrue(firstWriteEntered.await(2, TimeUnit.SECONDS))

        drain.offer(byteArrayOf(2, 2, 2, 2))
        drain.offer(byteArrayOf(3, 3, 3, 3))
        val overflow = drain.offer(byteArrayOf(4, 4, 4, 4))

        assertEquals(1, overflow.droppedChunks)
        assertEquals(4, overflow.droppedBytes)
        assertEquals(8, overflow.queuedBytes)

        releaseFirstWrite.countDown()
        assertTrue(allExpectedWrites.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(1, 3, 4), writes.toList())

        drain.stopAndFlush()
    }

    @Test
    fun `oversized chunk keeps only its newest bytes within the budget`() {
        val firstWriteEntered = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val secondWriteCompleted = CountDownLatch(1)
        val writes = Collections.synchronizedList(mutableListOf<ByteArray>())
        val drain = BoundedAudioDrain(
            maxQueuedBytes = 8,
            threadName = "bounded-audio-oversized-test",
            onWrite = { data ->
                writes += data
                if (writes.size == 1) {
                    firstWriteEntered.countDown()
                    assertTrue(releaseFirstWrite.await(2, TimeUnit.SECONDS))
                } else {
                    secondWriteCompleted.countDown()
                }
            },
            onPause = {},
            onFlush = {},
        )

        drain.start()
        drain.offer(byteArrayOf(99))
        assertTrue(firstWriteEntered.await(2, TimeUnit.SECONDS))
        val result = drain.offer(ByteArray(12) { it.toByte() })

        assertEquals(1, result.droppedChunks)
        assertEquals(4, result.droppedBytes)
        assertEquals(8, result.queuedBytes)

        releaseFirstWrite.countDown()
        assertTrue(secondWriteCompleted.await(2, TimeUnit.SECONDS))
        assertArrayEquals(ByteArray(8) { (it + 4).toByte() }, writes[1])
        drain.stopAndFlush()
    }

    @Test
    fun `stop flushes after in flight write and queued audio cannot cross generation`() {
        val firstWriteEntered = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val freshWriteCompleted = CountDownLatch(1)
        val pauseApplied = CountDownLatch(1)
        val writes = Collections.synchronizedList(mutableListOf<Int>())
        val events = Collections.synchronizedList(mutableListOf<String>())
        val drain = BoundedAudioDrain(
            maxQueuedBytes = 16,
            threadName = "bounded-audio-stop-test",
            onWrite = { data ->
                val id = data.first().toInt()
                writes += id
                events += "write-$id"
                if (id == 1) {
                    firstWriteEntered.countDown()
                    assertTrue(releaseFirstWrite.await(2, TimeUnit.SECONDS))
                } else if (id == 3) {
                    freshWriteCompleted.countDown()
                }
            },
            onPause = {
                events += "pause"
                pauseApplied.countDown()
            },
            onFlush = { events += "flush" },
        )

        val firstGeneration = drain.start()
        drain.offer(byteArrayOf(1, 1, 1, 1))
        assertTrue(firstWriteEntered.await(2, TimeUnit.SECONDS))
        drain.offer(byteArrayOf(2, 2, 2, 2))

        var stopResult: BoundedAudioDrain.StopResult? = null
        val stopper = Thread {
            stopResult = drain.stopAndFlush()
        }.also { it.start() }

        assertTrue("stop must pause the sink before waiting for its write", pauseApplied.await(2, TimeUnit.SECONDS))
        assertTrue("stop must wait until the in-flight write can be flushed", stopper.isAlive)
        releaseFirstWrite.countDown()
        stopper.join(2_000)
        assertFalse("stop must complete after the in-flight write returns", stopper.isAlive)

        assertEquals(1, stopResult?.droppedChunks)
        assertEquals(4, stopResult?.droppedBytes)
        assertEquals(listOf(1), writes.toList())
        assertEquals(listOf("write-1", "pause", "flush"), events.toList())

        val secondGeneration = drain.start()
        assertTrue(secondGeneration > firstGeneration)
        drain.offer(byteArrayOf(3, 3, 3, 3))
        assertTrue(freshWriteCompleted.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(1, 3), writes.toList())

        drain.stopAndFlush()
    }

    @Test
    fun `stop accounts for chunk claimed before sink lock`() {
        val chunkClaimed = CountDownLatch(1)
        val releaseClaim = CountDownLatch(1)
        val chunkFinished = CountDownLatch(1)
        val writes = Collections.synchronizedList(mutableListOf<Int>())
        val drain = BoundedAudioDrain(
            maxQueuedBytes = 8,
            threadName = "bounded-audio-claimed-stop-test",
            onWrite = { writes += it.first().toInt() },
            onPause = {},
            onFlush = {},
            onChunkClaimed = {
                chunkClaimed.countDown()
                assertTrue(releaseClaim.await(2, TimeUnit.SECONDS))
            },
            onChunkFinished = { chunkFinished.countDown() },
        )

        drain.start()
        drain.offer(byteArrayOf(7, 7, 7, 7))
        assertTrue(chunkClaimed.await(2, TimeUnit.SECONDS))

        val result = drain.stopAndFlush()
        assertEquals(1, result.droppedChunks)
        assertEquals(4, result.droppedBytes)

        releaseClaim.countDown()
        assertTrue(chunkFinished.await(2, TimeUnit.SECONDS))
        assertTrue(writes.isEmpty())
    }
}
