package com.openautolink.companion.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHealthTrackerTest {

    @Test
    fun `first sample reports absolute process health without invented deltas`() {
        val tracker = RuntimeHealthTracker()

        val report = tracker.record(
            reason = "service_start",
            sample = sample(
                elapsedMs = 1_000L,
                cpuMs = 250L,
                javaUsedBytes = 32L * MIB,
                javaMaxBytes = 256L * MIB,
                nativeUsedBytes = 12L * MIB,
                pssKb = 64L * 1024L,
                rssKb = 80L * 1024L,
                threads = 18,
                fds = 42,
            ),
        )

        assertTrue(report.line.startsWith("COMPANION HEALTH reason=service_start pressure=normal"))
        assertTrue(report.line.contains("javaMiB=32/256 javaPct=12"))
        assertTrue(report.line.contains("nativeMiB=12 pssMiB=64 rssMiB=80"))
        assertTrue(report.line.contains("threads=18 fds=42"))
        assertTrue(report.line.contains("cpuPct=na dPssKiB=na dRssKiB=na dFds=na"))
        assertFalse(report.isPressureWarning)
    }

    @Test
    fun `later sample reports memory fd and process cpu trends`() {
        val tracker = RuntimeHealthTracker()
        tracker.record(
            "service_start",
            sample(
                elapsedMs = 1_000L,
                cpuMs = 100L,
                pssKb = 40_000L,
                rssKb = 50_000L,
                fds = 30,
            ),
        )

        val report = tracker.record(
            "periodic",
            sample(
                elapsedMs = 61_000L,
                cpuMs = 1_300L,
                pssKb = 43_000L,
                rssKb = 54_000L,
                fds = 34,
            ),
        )

        assertTrue(report.line.contains("cpuPct=2.0"))
        assertTrue(report.line.contains("dPssKiB=3000 dRssKiB=4000 dFds=4"))
    }

    @Test
    fun `high heap or fd pressure elevates the report to warning`() {
        val heapPressure = RuntimeHealthTracker().record(
            "periodic",
            sample(
                javaUsedBytes = 220L * MIB,
                javaMaxBytes = 256L * MIB,
            ),
        )
        val fdPressure = RuntimeHealthTracker().record(
            "periodic",
            sample(fds = RuntimeHealthTracker.FD_WARNING_THRESHOLD),
        )

        assertEquals("heap_high", heapPressure.pressure)
        assertTrue(heapPressure.isPressureWarning)
        assertEquals("fd_high", fdPressure.pressure)
        assertTrue(fdPressure.isPressureWarning)
    }

    @Test
    fun `trim callbacks retain their exact level in the stable marker`() {
        val report = RuntimeHealthTracker().record(
            reason = "trim_memory_80",
            sample = sample(),
        )

        assertTrue(report.line.startsWith("COMPANION HEALTH reason=trim_memory_80"))
    }

    @Test
    fun `system memory pressure is explicit in every report`() {
        val report = RuntimeHealthTracker().record(
            reason = "periodic",
            sample = sample(
                systemAvailBytes = 768L * MIB,
                systemThresholdBytes = 1024L * MIB,
                systemLowMemory = true,
            ),
        )

        assertEquals("system_low", report.pressure)
        assertTrue(report.isPressureWarning)
        assertTrue(report.line.contains("systemAvailMiB=768 systemThresholdMiB=1024 systemLow=true"))
    }

    @Test
    fun `unavailable process metrics stay unavailable instead of becoming zero`() {
        val report = RuntimeHealthTracker().record(
            reason = "periodic",
            sample = sample(
                nativeUsedBytes = -1L,
                pssKb = -1L,
                rssKb = -1L,
                threads = -1,
                fds = -1,
                systemAvailBytes = -1L,
                systemThresholdBytes = -1L,
            ),
        )

        assertTrue(report.line.contains("nativeMiB=na pssMiB=na rssMiB=na"))
        assertTrue(report.line.contains("threads=na fds=na"))
        assertTrue(report.line.contains("systemAvailMiB=na systemThresholdMiB=na"))
    }

    @Test
    fun `normal baselines are info while pressure callbacks are warnings`() {
        val normal = RuntimeHealthTracker().record("service_start", sample())
        val trim = RuntimeHealthTracker().record("trim_memory_80", sample())

        assertFalse(normal.shouldLogWarning("service_start"))
        assertTrue(trim.shouldLogWarning("trim_memory_80"))
    }

    private fun sample(
        elapsedMs: Long = 1_000L,
        cpuMs: Long = 100L,
        javaUsedBytes: Long = 32L * MIB,
        javaMaxBytes: Long = 256L * MIB,
        nativeUsedBytes: Long = 12L * MIB,
        pssKb: Long = 64L * 1024L,
        rssKb: Long = 80L * 1024L,
        threads: Int = 18,
        fds: Int = 42,
        systemAvailBytes: Long = 4L * 1024L * MIB,
        systemThresholdBytes: Long = 512L * MIB,
        systemLowMemory: Boolean = false,
    ) = RuntimeHealthSample(
        elapsedMs = elapsedMs,
        processCpuMs = cpuMs,
        javaUsedBytes = javaUsedBytes,
        javaMaxBytes = javaMaxBytes,
        nativeUsedBytes = nativeUsedBytes,
        pssKb = pssKb,
        rssKb = rssKb,
        threadCount = threads,
        openFdCount = fds,
        systemAvailBytes = systemAvailBytes,
        systemThresholdBytes = systemThresholdBytes,
        systemLowMemory = systemLowMemory,
    )

    companion object {
        private const val MIB = 1024L * 1024L
    }
}
