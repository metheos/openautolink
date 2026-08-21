package com.openautolink.app.session

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionStatsBindingContractTest {
    private fun projectFile(relative: String): String {
        val start = File(requireNotNull(System.getProperty("user.dir")))
        val root = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not locate project root from $start")
        return File(root, relative).readText()
    }

    @Test
    fun `decoder replacement cancels stale stats collectors and binds the current decoder`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt",
        )

        assertTrue(source.contains("private var videoStatsJob: Job? = null"))
        assertTrue(source.contains("private fun bindCurrentStatsCollectors()"))
        assertTrue(source.contains("videoStatsJob?.cancel()"))
        assertTrue(source.contains("_videoStats.value = VideoStats()"))
        assertTrue(source.contains("sessionManager.videoStats?.let"))
        assertTrue(source.contains("bindCurrentStatsCollectors()"))
        assertTrue(source.contains("sessionManager.onDecoderCreated ="))
        assertTrue(source.contains("Stats collectors rebound:"))
        assertTrue(source.contains("sessionManager.onDecoderCreated = null"))
        assertTrue(source.contains("videoStatsJob?.cancel()"))
        assertTrue(source.contains("audioStatsJob?.cancel()"))
    }
}