package com.openautolink.companion.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Low-frequency process health telemetry for drive logs. Sampling runs on a
 * child of the service IO scope and never blocks socket/bridge callbacks.
 */
class CompanionRuntimeHealthMonitor(
    context: Context,
    parentScope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val tracker = RuntimeHealthTracker()
    private val monitorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val monitorScope = CoroutineScope(parentScope.coroutineContext + monitorJob)
    private val lifecycleLock = Any()
    private var periodicJob: Job? = null

    @Volatile
    private var stopped = false

    fun start() {
        synchronized(lifecycleLock) {
            if (periodicJob?.isActive == true || !monitorJob.isActive) return
            stopped = false
            periodicJob = monitorScope.launch {
                emitSample("service_start")
                while (isActive) {
                    delay(SAMPLE_INTERVAL_MS)
                    emitSample("periodic")
                }
            }
        }
    }

    /** Queue a pressure-triggered sample without doing file/proc work on the callback thread. */
    fun record(reason: String) {
        synchronized(lifecycleLock) {
            if (stopped || !monitorJob.isActive) return
            monitorScope.launch { emitSample(reason) }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            stopped = true
            monitorJob.cancel()
            periodicJob = null
        }
    }

    private fun emitSample(reason: String) {
        val report = try {
            tracker.record(reason, sample())
        } catch (error: Exception) {
            synchronized(lifecycleLock) {
                if (stopped) return
                CompanionLog.w(
                    TAG,
                    "COMPANION HEALTH sampling_failed reason=$reason " +
                        "error=${error.javaClass.simpleName}",
                )
            }
            return
        }

        synchronized(lifecycleLock) {
            if (stopped) return
            if (report.shouldLogWarning(reason)) {
                CompanionLog.w(TAG, report.line)
            } else {
                CompanionLog.i(TAG, report.line)
            }
        }
    }

    private fun sample(): RuntimeHealthSample {
        val runtime = Runtime.getRuntime()
        val processMemory = Debug.MemoryInfo()
        Debug.getMemoryInfo(processMemory)
        val systemMemory = ActivityManager.MemoryInfo().also { memoryInfo ->
            appContext.getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
        }
        val procStatus = readProcStatus()
        return RuntimeHealthSample(
            elapsedMs = SystemClock.elapsedRealtime(),
            processCpuMs = Process.getElapsedCpuTime(),
            javaUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            javaMaxBytes = runtime.maxMemory(),
            nativeUsedBytes = Debug.getNativeHeapAllocatedSize(),
            pssKb = processMemory.totalPss.toLong(),
            rssKb = procStatus.rssKb,
            threadCount = procStatus.threadCount,
            openFdCount = File("/proc/self/fd").list()?.size ?: -1,
            systemAvailBytes = systemMemory.availMem,
            systemThresholdBytes = systemMemory.threshold,
            systemLowMemory = systemMemory.lowMemory,
        )
    }

    private fun readProcStatus(): ProcStatus {
        var rssKb = -1L
        var threadCount = -1
        File("/proc/self/status").forEachLine { line ->
            when {
                line.startsWith("VmRSS:") -> rssKb = firstLong(line)
                line.startsWith("Threads:") -> threadCount = firstLong(line).toInt()
            }
        }
        return ProcStatus(rssKb = rssKb, threadCount = threadCount)
    }

    private fun firstLong(line: String): Long =
        line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: -1L

    private data class ProcStatus(
        val rssKb: Long,
        val threadCount: Int,
    )

    companion object {
        private const val TAG = "OAL_Health"
        internal const val SAMPLE_INTERVAL_MS = 60_000L
    }
}
