package com.openautolink.companion.diagnostics

import java.util.Locale

/** One low-frequency snapshot of this process's resource use. */
data class RuntimeHealthSample(
    val elapsedMs: Long,
    val processCpuMs: Long,
    val javaUsedBytes: Long,
    val javaMaxBytes: Long,
    val nativeUsedBytes: Long,
    val pssKb: Long,
    val rssKb: Long,
    val threadCount: Int,
    val openFdCount: Int,
    val systemAvailBytes: Long,
    val systemThresholdBytes: Long,
    val systemLowMemory: Boolean,
)

data class RuntimeHealthReport(
    val line: String,
    val pressure: String,
    val isPressureWarning: Boolean,
) {
    fun shouldLogWarning(reason: String): Boolean =
        isPressureWarning || reason == "low_memory_callback" || reason.startsWith("trim_memory_")
}

/**
 * Formats bounded, identifier-free resource snapshots and derives trends from
 * the immediately previous sample. The tracker is synchronized because periodic
 * and trim-memory samples can arrive from different threads.
 */
class RuntimeHealthTracker {
    private var previous: RuntimeHealthSample? = null

    @Synchronized
    fun record(reason: String, sample: RuntimeHealthSample): RuntimeHealthReport {
        val prior = previous
        previous = sample

        val javaPercent = percentage(sample.javaUsedBytes, sample.javaMaxBytes)
        val pressure = when {
            sample.systemLowMemory -> "system_low"
            javaPercent >= HEAP_WARNING_PERCENT &&
                sample.openFdCount >= FD_WARNING_THRESHOLD -> "heap_fd_high"
            javaPercent >= HEAP_WARNING_PERCENT -> "heap_high"
            sample.openFdCount >= FD_WARNING_THRESHOLD -> "fd_high"
            else -> "normal"
        }
        val cpuPercent = prior?.let {
            val elapsedDelta = sample.elapsedMs - it.elapsedMs
            val cpuDelta = sample.processCpuMs - it.processCpuMs
            if (elapsedDelta > 0L && cpuDelta >= 0L) {
                String.format(Locale.US, "%.1f", cpuDelta * 100.0 / elapsedDelta)
            } else {
                "na"
            }
        } ?: "na"

        val line = buildString(320) {
            append("COMPANION HEALTH reason=").append(sanitizeReason(reason))
            append(" pressure=").append(pressure)
            append(" elapsedMs=").append(sample.elapsedMs)
            append(" javaMiB=").append(toMiB(sample.javaUsedBytes))
                .append('/').append(toMiB(sample.javaMaxBytes))
            append(" javaPct=").append(javaPercent)
            append(" nativeMiB=").append(toMiBOrUnavailable(sample.nativeUsedBytes))
            append(" pssMiB=").append(toKiBMiBOrUnavailable(sample.pssKb))
            append(" rssMiB=").append(toKiBMiBOrUnavailable(sample.rssKb))
            append(" threads=").append(countOrUnavailable(sample.threadCount))
            append(" fds=").append(countOrUnavailable(sample.openFdCount))
            append(" systemAvailMiB=").append(toMiBOrUnavailable(sample.systemAvailBytes))
            append(" systemThresholdMiB=").append(toMiBOrUnavailable(sample.systemThresholdBytes))
            append(" systemLow=").append(sample.systemLowMemory)
            append(" cpuPct=").append(cpuPercent)
            append(" dPssKiB=").append(delta(prior?.pssKb, sample.pssKb))
            append(" dRssKiB=").append(delta(prior?.rssKb, sample.rssKb))
            append(" dFds=").append(delta(prior?.openFdCount?.toLong(), sample.openFdCount.toLong()))
        }
        return RuntimeHealthReport(
            line = line,
            pressure = pressure,
            isPressureWarning = pressure != "normal",
        )
    }

    private fun percentage(value: Long, maximum: Long): Int =
        if (maximum <= 0L) 0 else ((value.coerceAtLeast(0L) * 100L) / maximum).toInt()

    private fun toMiB(bytes: Long): Long = bytes.coerceAtLeast(0L) / BYTES_PER_MIB

    private fun toMiBOrUnavailable(bytes: Long): String =
        if (bytes < 0L) "na" else (bytes / BYTES_PER_MIB).toString()

    private fun toKiBMiBOrUnavailable(kibibytes: Long): String =
        if (kibibytes < 0L) "na" else (kibibytes / KIB_PER_MIB).toString()

    private fun countOrUnavailable(count: Int): String = if (count < 0) "na" else count.toString()

    private fun delta(previous: Long?, current: Long): String =
        previous?.takeIf { it >= 0L }
            ?.takeIf { current >= 0L }
            ?.let { (current - it).toString() }
            ?: "na"

    private fun sanitizeReason(reason: String): String =
        reason.take(MAX_REASON_LENGTH).map { c ->
            if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_'
        }.joinToString("").ifBlank { "unknown" }

    companion object {
        const val HEAP_WARNING_PERCENT = 80
        const val FD_WARNING_THRESHOLD = 512
        private const val MAX_REASON_LENGTH = 48
        private const val BYTES_PER_MIB = 1024L * 1024L
        private const val KIB_PER_MIB = 1024L
    }
}
