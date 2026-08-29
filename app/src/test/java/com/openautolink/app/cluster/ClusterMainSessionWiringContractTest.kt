package com.openautolink.app.cluster

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterMainSessionWiringContractTest {

    private fun projectFile(relative: String): String {
        val start = File(requireNotNull(System.getProperty("user.dir")))
        val root = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
        return File(requireNotNull(root), relative).readText()
    }

    @Test
    fun navigationOwnershipIsRouteGatedAndHonorsHostStop() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/cluster/ClusterMainSession.kt",
        )
        val stopStart = source.indexOf("override fun onStopNavigation()")
        val stopEnd = source.indexOf("override fun onAutoDriveEnabled()", stopStart)
        assertTrue("onStopNavigation callback missing", stopStart >= 0 && stopEnd > stopStart)
        val stopCallback = source.substring(stopStart, stopEnd)

        assertTrue(source.contains("ClusterNavigationLifecyclePolicy()"))
        assertTrue(source.contains("navigationLifecycle.onRouteAvailable()"))
        assertTrue(source.contains("navigationLifecycle.onRouteCleared()"))
        assertTrue(stopCallback.contains("navigationLifecycle.onHostStop()"))
        assertFalse(stopCallback.contains("navigationStarted()"))
        assertFalse(source.contains("Call navigationStarted() IMMEDIATELY"))
    }

    @Test
    fun bootstrapTemplateDoesNotExposeIssue116Placeholder() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/cluster/ClusterMainSession.kt",
        )

        assertFalse(source.contains("OpenAutoLink — Cluster Navigation"))
        assertFalse(source.contains("Cluster navigation service active."))
    }
}
