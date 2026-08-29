package com.openautolink.companion.diagnostics

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHealthIntegrationContractTest {

    @Test
    fun `service owns runtime health monitoring for its complete lifecycle`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/service/CompanionService.kt",
        ).readText()
        val onCreate = source.substringAfter("override fun onCreate()")
            .substringBefore("override fun onStartCommand")
        val onDestroy = source.substringAfter("override fun onDestroy()")
            .substringBefore("private fun joinWppAttempt")

        assertTrue(onCreate.indexOf("logPreviousProcessExit()") >= 0)
        assertTrue(onCreate.indexOf("runtimeHealthMonitor") > onCreate.indexOf("logPreviousProcessExit()"))
        assertTrue(onCreate.contains("if (_fileLoggingActive.value)"))
        assertTrue(onCreate.lastIndexOf("record(\"logging_start\")") > onCreate.indexOf("runtimeHealthMonitor"))
        assertTrue(source.contains("override fun onTrimMemory(level: Int)"))
        assertTrue(source.contains("record(\"trim_memory_${'$'}level\")"))
        assertTrue(source.contains("override fun onLowMemory()"))
        assertTrue(source.contains("record(\"low_memory_callback\")"))
        assertTrue(onDestroy.indexOf("runtimeHealthMonitor?.stop()") >= 0)
        assertTrue(onDestroy.indexOf("runtimeHealthMonitor?.stop()") < onDestroy.indexOf("serviceScope.cancel()"))

        val startLogging = source.substringAfter("fun startFileLogging()")
            .substringBefore("fun stopFileLogging()")
        assertTrue(startLogging.indexOf("_fileLoggingActive.value = true") >= 0)
        assertTrue(
            startLogging.indexOf("record(\"logging_start\")") >
                startLogging.indexOf("_fileLoggingActive.value = true"),
        )
    }

    @Test
    fun `sampler is low frequency bounded and avoids thread stack allocation`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/diagnostics/CompanionRuntimeHealthMonitor.kt",
        ).readText()

        assertTrue(source.contains("SAMPLE_INTERVAL_MS = 60_000L"))
        assertTrue(source.contains("/proc/self/status"))
        assertTrue(source.contains("/proc/self/fd"))
        assertTrue(source.contains("Debug.getMemoryInfo"))
        assertTrue(source.contains("SupervisorJob"))
        assertTrue(source.contains("monitorJob.cancel()"))
        assertTrue(source.contains("catch (error: Exception)"))
        assertFalse(source.contains("runCatching"))
        assertFalse(source.contains("Thread.getAllStackTraces"))
        assertFalse(source.contains("while (true)"))
    }

    @Test
    fun `previous low memory and resource exits receive a stable memory marker`() {
        assertTrue(ProcessExitSummary.isMemoryPressureReason(3))
        assertFalse(ProcessExitSummary.isMemoryPressureReason(9))
        assertFalse(ProcessExitSummary.isMemoryPressureReason(4))
        assertTrue(ProcessExitSummary.isResourcePressureReason(3))
        assertTrue(ProcessExitSummary.isResourcePressureReason(9))
        assertFalse(ProcessExitSummary.isResourcePressureReason(4))

        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/service/CompanionService.kt",
        ).readText()
        assertTrue(source.contains("COMPANION MEMORY EXIT"))
        assertTrue(source.contains("COMPANION RESOURCE EXIT"))
    }

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate project file: $path")
    }
}