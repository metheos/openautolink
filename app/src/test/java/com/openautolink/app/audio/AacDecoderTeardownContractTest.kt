package com.openautolink.app.audio

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AacDecoderTeardownContractTest {

    @Test
    fun `stop joins decoder thread before releasing MediaCodec`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/audio/AacDecoder.kt",
        ).readText()
        val stop = source.substringAfter("fun stop()")
            .substringBefore("fun queueAacFrame(")

        val runningFalse = stop.indexOf("running = false")
        val joinLoop = stop.indexOf("while (thread?.isAlive == true)")
        val join = stop.indexOf("thread.join()")
        val codecStop = stop.indexOf("codec?.stop()")
        val codecRelease = stop.indexOf("codec?.release()")

        assertTrue(runningFalse >= 0)
        assertTrue(joinLoop > runningFalse)
        assertTrue(join > joinLoop)
        assertTrue(codecStop > join)
        assertTrue(codecRelease > codecStop)
        assertTrue(stop.contains("Thread.currentThread().interrupt()"))
    }

    private fun projectFile(path: String): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir unavailable"))
        repeat(8) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate $path")
    }
}
