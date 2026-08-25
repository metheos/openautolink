package com.openautolink.app.audio

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPurposeSlotWiringContractTest {

    @Test
    fun `purpose slot bounds pending latency and records ingress before queueing`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/audio/AudioPurposeSlot.kt",
        ).readText()

        assertTrue(source.contains("MAX_PENDING_AUDIO_MS = 250"))
        assertTrue(source.contains("BoundedAudioDrain("))
        assertTrue(source.contains("maxQueuedBytes ="))

        val feed = source.substringAfter("fun feedPcm(data: ByteArray)")
            .substringBefore("fun setVolume(")
        assertTrue(feed.contains("lastFeedTimeNs = nowNs"))
        assertTrue(feed.contains("drain?.offer(data)"))
        assertTrue(
            "Idle time must measure phone ingress, not delayed AudioTrack writes",
            feed.indexOf("lastFeedTimeNs = nowNs") < feed.indexOf("drain?.offer(data)"),
        )
        assertTrue(feed.contains("Dropped stale PCM:"))
        assertTrue(source.contains("AudioTrack.WRITE_NON_BLOCKING"))
        assertTrue(source.contains("writtenBytes < data.size"))
        assertTrue(source.contains("recordStaleDrop("))
        assertTrue(source.contains("val pendingAudioBytes:"))
        assertTrue(source.contains("val staleAudioBytesDropped:"))

        val player = projectFile(
            "app/src/main/java/com/openautolink/app/audio/AudioPlayerImpl.kt",
        ).readText()
        assertTrue(player.contains("pending=${'$'}{slot.pendingAudioBytes}"))
        assertTrue(player.contains("staleDropped=${'$'}{slot.staleAudioBytesDropped}"))
        assertTrue(player.contains("private val slotLifecycleLock = Any()"))
        val playerFrame = player.substringAfter("override fun onAudioFrame(")
            .substringBefore("override fun startPurpose(")
        val playerStart = player.substringAfter("override fun startPurpose(")
            .substringBefore("override fun stopPurpose(")
        val playerStop = player.substringAfter("override fun stopPurpose(")
            .substringBefore("override fun setVolume(")
        assertTrue(playerFrame.contains("synchronized(slotLifecycleLock)"))
        assertTrue(playerFrame.contains("val slot = resolvePlaybackSlot(frame) ?: return@synchronized"))
        assertTrue(playerFrame.contains("slot.feedPcm(frame.data)"))
        assertTrue(
            "Frame submission must stay inside the same owner lock as slot resolution",
            playerFrame.indexOf("synchronized(slotLifecycleLock)") <
                playerFrame.indexOf("val slot = resolvePlaybackSlot(frame)") &&
                playerFrame.indexOf("val slot = resolvePlaybackSlot(frame)") <
                playerFrame.indexOf("slot.feedPcm(frame.data)"),
        )
        val resolver = player.substringAfter("private fun resolvePlaybackSlot(")
            .substringBefore("override fun startPurpose(")
        assertTrue(resolver.contains("synchronized(slotLifecycleLock)"))
        assertTrue(resolver.contains("explicitStopTimes[frame.purpose]"))
        assertTrue(resolver.contains("startPurpose(frame.purpose"))
        assertTrue(playerStart.contains("synchronized(slotLifecycleLock)"))
        assertTrue(playerStop.contains("synchronized(slotLifecycleLock)"))
    }

    @Test
    fun `stop and focus loss retire the exact drain before playback can resume`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/audio/AudioPurposeSlot.kt",
        ).readText()

        val stop = source.substringAfter("fun stop()")
            .substringBefore("fun pause()")
        val pause = source.substringAfter("fun pause()")
            .substringBefore("fun resume()")
        val resume = source.substringAfter("fun resume()")
            .substringBefore("val isPausedByFocus")

        assertTrue(stop.contains("retireDrain(reason = \"stop\")"))
        assertTrue(pause.contains("retireDrain(reason = \"focus-loss\")"))
        assertTrue(resume.contains("startDrain(track)"))
        assertTrue(source.contains("private val lifecycleLock = Any()"))
        assertTrue(source.contains("@Volatile private var drain:"))
        val start = source.substringAfter("fun start()")
            .substringBefore("fun stop()")
        assertTrue(start.contains("synchronized(lifecycleLock)"))
        assertTrue(stop.contains("synchronized(lifecycleLock)"))
        assertTrue(
            "The slot must not accept frames until its exact writer exists",
            start.indexOf("startDrain(track)") < start.indexOf("active.set(true)"),
        )
        assertTrue(source.contains("Audio stop applied:"))
        assertTrue(source.contains("flushed=true"))
        val aacFeed = source.substringAfter("fun feedAac(data: ByteArray)")
            .substringBefore("fun release()")
        assertTrue(aacFeed.contains("aacDecoder === decoder && active.get()"))
        val retire = source.substringAfter("private fun retireDrain(reason: String)")
            .substringBefore("fun setVolume(")
        assertTrue(retire.contains("val retiredDecoder = aacDecoder"))
        assertTrue(retire.indexOf("aacDecoder = null") < retire.indexOf("retiredDecoder?.stop()"))
        assertTrue(retire.contains("aacRetired=${'$'}{retiredDecoder != null}"))
        val drain = projectFile(
            "app/src/main/java/com/openautolink/app/audio/BoundedAudioDrain.kt",
        ).readText()
        val drainStop = drain.substringAfter("fun stopAndFlush()")
            .substringBefore("private fun drainLoop")
        assertTrue(drainStop.indexOf("onPause()") < drainStop.indexOf("synchronized(writeLock)"))
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
