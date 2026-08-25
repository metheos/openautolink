package com.openautolink.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.transport.AudioPurpose
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One AudioTrack plus a generation-owned bounded writer per audio purpose.
 *
 * - Pending PCM is capped at 250 ms; overflow discards oldest queued chunks.
 * - AudioTrack.write() runs on a dedicated URGENT_AUDIO thread per purpose.
 * - Stop/focus loss retire the exact writer generation before flushing.
 * - Phone-ingress time, sink write time, and stale-drop totals are tracked
 *   separately so uploaded logs reveal latency instead of merely throughput.
 */
class AudioPurposeSlot(
    val purpose: AudioPurpose,
    val sampleRate: Int,
    val channelCount: Int,
    private val bufferDurationMs: Int = 500
) {
    companion object {
        private const val TAG = "AudioPurposeSlot"
        private const val MAX_PENDING_AUDIO_MS = 250
        private const val DROP_LOG_INTERVAL_NS = 2_000_000_000L
    }

    private var audioTrack: AudioTrack? = null
    private var aacDecoder: AacDecoder? = null
    private val lifecycleLock = Any()

    /** One bounded, generation-owned writer per active interval. */
    @Volatile private var drain: BoundedAudioDrain? = null

    private val active = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val pausedByFocusLoss = AtomicBoolean(false)

    val framesWritten = AtomicLong(0)
    val underrunCount = AtomicLong(0)
    private val staleBytesDropped = AtomicLong(0)

    // Diagnostic counters
    @Volatile var startedAtNs: Long = 0L
    @Volatile var lastFeedTimeNs: Long = 0L
    @Volatile var maxWriteMs: Long = 0L
    @Volatile var maxGapMs: Long = 0L
    @Volatile var totalWriteCalls: Long = 0L
    @Volatile var slowWriteCount: Long = 0L  // writes > 60ms
    @Volatile var hwUnderrunCount: Long = 0L
    private val dropStatsLock = Any()
    @Volatile private var lastDropLogNs: Long = 0L
    @Volatile private var droppedChunksSinceLog: Long = 0L
    @Volatile private var droppedBytesSinceLog: Long = 0L

    fun initialize() {
        if (released.get()) return

        val channelMask = if (channelCount == 2)
            AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        // Use 4x min buffer — same as app_v1 production
        val trackBufSize = maxOf(minBufSize * 4, 16384)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(buildAudioAttributes(purpose))
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(trackBufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        Log.d(TAG, "Initialized $purpose: ${sampleRate}Hz ${channelCount}ch, track=${trackBufSize}B")
    }

    fun start() {
        synchronized(lifecycleLock) {
            if (released.get() || active.get()) return@synchronized
            val track = audioTrack ?: return@synchronized
            startedAtNs = System.nanoTime()
            track.play()
            startDrain(track)
            active.set(true)
            Log.d(TAG, "$purpose started (bounded per-purpose writer)")
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            pausedByFocusLoss.set(false)
            if (!active.getAndSet(false)) return@synchronized
            startedAtNs = 0L
            lastFeedTimeNs = 0L
            retireDrain(reason = "stop")
            Log.d(TAG, "$purpose stopped")
        }
    }

    fun pause() {
        synchronized(lifecycleLock) {
            if (!active.getAndSet(false)) return@synchronized
            pausedByFocusLoss.set(true)
            retireDrain(reason = "focus-loss")
            Log.d(TAG, "$purpose paused")
        }
    }

    fun resume() {
        synchronized(lifecycleLock) {
            if (released.get() || active.get()) return@synchronized
            if (!pausedByFocusLoss.getAndSet(false)) return@synchronized
            val track = audioTrack ?: return@synchronized
            startedAtNs = System.nanoTime()
            lastFeedTimeNs = 0L
            track.play()
            startDrain(track)
            active.set(true)
            Log.d(TAG, "$purpose resumed")
        }
    }

    val isPausedByFocus: Boolean get() = pausedByFocusLoss.get()

    /** Submit PCM without allowing stale pending audio to grow unbounded. */
    fun feedPcm(data: ByteArray) {
        if (!active.get()) return
        // This is the phone-ingress clock. Updating it inside the delayed writer
        // made the idle timer wait for stale queued audio to finish first.
        val nowNs = System.nanoTime()
        val prevNs = lastFeedTimeNs
        lastFeedTimeNs = nowNs
        if (prevNs > 0) {
            val gapMs = (nowNs - prevNs) / 1_000_000
            if (gapMs > maxGapMs) maxGapMs = gapMs
        }

        val result = drain?.offer(data) ?: return
        if (result.droppedChunks > 0) {
            recordStaleDrop(
                chunks = result.droppedChunks,
                bytes = result.droppedBytes,
                pendingBytes = result.queuedBytes,
                source = "queue",
                nowNs = nowNs,
            )
        }
    }

    private fun startDrain(track: AudioTrack) {
        val bytesPerSecond = sampleRate.toLong() * channelCount * 2L
        val maxQueuedBytes = (bytesPerSecond * MAX_PENDING_AUDIO_MS / 1000L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val newDrain = BoundedAudioDrain(
            maxQueuedBytes = maxQueuedBytes,
            threadName = "AudioWrite-$purpose",
            onWorkerStart = { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) },
            onWrite = { pcm -> writePcm(track, pcm) },
            onPause = { track.pause() },
            onFlush = { track.flush() },
        )
        drain = newDrain
        val generation = newDrain.start()
        DiagnosticLog.i(
            "audio",
            "Audio drain started: purpose=$purpose generation=$generation latencyCapMs=$MAX_PENDING_AUDIO_MS",
        )
    }

    private fun writePcm(track: AudioTrack, data: ByteArray) {
        val writeStartNs = System.nanoTime()
        val writtenBytes = track.write(
            data,
            0,
            data.size,
            AudioTrack.WRITE_NON_BLOCKING,
        )
        val writeMs = (System.nanoTime() - writeStartNs) / 1_000_000

        totalWriteCalls++
        if (writtenBytes > 0) {
            framesWritten.addAndGet(writtenBytes.toLong() / (channelCount * 2))
        }
        if (writtenBytes < data.size) {
            val staleBytes = if (writtenBytes > 0) data.size - writtenBytes else data.size
            recordStaleDrop(
                chunks = 1,
                bytes = staleBytes,
                pendingBytes = drain?.pendingBytes ?: 0,
                source = "sink",
                nowNs = System.nanoTime(),
            )
        }
        if (writtenBytes < 0) {
            DiagnosticLog.w("audio", "AudioTrack write failed: purpose=$purpose result=$writtenBytes")
        }
        if (writeMs > maxWriteMs) maxWriteMs = writeMs
        if (writeMs > 60) slowWriteCount++
        if (totalWriteCalls % 50 == 0L) {
            try {
                hwUnderrunCount = track.underrunCount.toLong()
            } catch (_: Exception) {}
        }
    }

    private fun recordStaleDrop(
        chunks: Int,
        bytes: Int,
        pendingBytes: Int,
        source: String,
        nowNs: Long,
    ) {
        if (bytes <= 0) return
        staleBytesDropped.addAndGet(bytes.toLong())
        synchronized(dropStatsLock) {
            droppedChunksSinceLog += chunks
            droppedBytesSinceLog += bytes
            if (lastDropLogNs == 0L || nowNs - lastDropLogNs >= DROP_LOG_INTERVAL_NS) {
                val message = "Dropped stale PCM: purpose=$purpose source=$source " +
                    "chunks=$droppedChunksSinceLog bytes=$droppedBytesSinceLog " +
                    "pending=$pendingBytes latencyCapMs=$MAX_PENDING_AUDIO_MS"
                Log.w(TAG, message)
                DiagnosticLog.w("audio", message)
                droppedChunksSinceLog = 0
                droppedBytesSinceLog = 0
                lastDropLogNs = nowNs
            }
        }
    }

    private fun retireDrain(reason: String) {
        val retiredDecoder = aacDecoder
        aacDecoder = null
        retiredDecoder?.stop()
        val retired = drain
        drain = null
        val result = if (retired != null) {
            retired.stopAndFlush()
        } else {
            audioTrack?.pause()
            audioTrack?.flush()
            null
        }
        if (result != null && result.droppedBytes > 0) {
            staleBytesDropped.addAndGet(result.droppedBytes.toLong())
        }
        val message = "Audio stop applied: purpose=$purpose reason=$reason " +
            "generation=${result?.generation ?: -1} " +
            "droppedQueuedChunks=${result?.droppedChunks ?: 0} " +
            "droppedQueuedBytes=${result?.droppedBytes ?: 0} " +
            "aacRetired=${retiredDecoder != null} flushed=true"
        Log.i(TAG, message)
        DiagnosticLog.i("audio", message)
    }

    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }

    /**
     * Feed an AAC-LC encoded frame. Creates an AacDecoder on first call
     * which decodes to PCM and routes through feedPcm().
     */
    fun feedAac(data: ByteArray) {
        if (!active.get()) return
        val existing = aacDecoder
        val decoder = if (existing != null) {
            existing
        } else {
            lateinit var decoder: AacDecoder
            decoder = AacDecoder(sampleRate, channelCount) { pcm ->
                if (aacDecoder === decoder && active.get()) feedPcm(pcm)
            }
            aacDecoder = decoder
            decoder.start()
            Log.i(TAG, "$purpose: AAC decoder started (${sampleRate}Hz ${channelCount}ch)")
            decoder
        }
        decoder.queueAacFrame(data)
    }

    fun release() {
        synchronized(lifecycleLock) {
            if (released.getAndSet(true)) return@synchronized
            stop()
            aacDecoder?.stop()
            aacDecoder = null
            drain = null
            audioTrack?.release()
            audioTrack = null
            Log.d(TAG, "$purpose released")
        }
    }

    val isActive: Boolean get() = active.get()
    val pendingAudioBytes: Int get() = drain?.pendingBytes ?: 0
    val staleAudioBytesDropped: Long get() = staleBytesDropped.get()
    val ringBufferAvailable: Int get() = 0
    val ringBufferCapacity: Int get() = 0

    /**
     * How long this slot has been idle (no frames received) in ms.
     * Returns -1 if the slot is not active.
     */
    fun idleMs(): Long {
        if (!active.get()) return -1
        val now = System.nanoTime()
        val lastFeed = lastFeedTimeNs
        if (lastFeed > 0) return (now - lastFeed) / 1_000_000
        // Never received a frame — use start time
        val started = startedAtNs
        return if (started > 0) (now - started) / 1_000_000 else -1
    }

    private fun buildAudioAttributes(purpose: AudioPurpose): AudioAttributes {
        val usage = when (purpose) {
            AudioPurpose.MEDIA -> AudioAttributes.USAGE_MEDIA
            AudioPurpose.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            AudioPurpose.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
            AudioPurpose.PHONE_CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            AudioPurpose.ALERT -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        }
        val contentType = when (purpose) {
            AudioPurpose.MEDIA -> AudioAttributes.CONTENT_TYPE_MUSIC
            AudioPurpose.PHONE_CALL -> AudioAttributes.CONTENT_TYPE_SPEECH
            AudioPurpose.ASSISTANT -> AudioAttributes.CONTENT_TYPE_SPEECH
            AudioPurpose.NAVIGATION -> AudioAttributes.CONTENT_TYPE_SPEECH
            AudioPurpose.ALERT -> AudioAttributes.CONTENT_TYPE_SONIFICATION
        }
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()
    }
}