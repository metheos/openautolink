package com.openautolink.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SurfaceRecoveryPolicyTest {

    @Test
    fun `successful surface swap keeps the live session`() {
        assertEquals(
            SurfaceRecoveryAction.KEEP_LIVE_CODEC,
            SurfaceRecoveryPolicy.afterSwap(
                swapSucceeded = true,
                hasCachedRealIdr = false,
            ),
        )
    }

    @Test
    fun `rejected swap with cached real IDR reconfigures locally`() {
        assertEquals(
            SurfaceRecoveryAction.RECONFIGURE_WITH_CACHED_IDR,
            SurfaceRecoveryPolicy.afterSwap(
                swapSucceeded = false,
                hasCachedRealIdr = true,
            ),
        )
    }

    @Test
    fun `rejected swap without cached real IDR restarts the session`() {
        assertEquals(
            SurfaceRecoveryAction.RESTART_SESSION,
            SurfaceRecoveryPolicy.afterSwap(
                swapSucceeded = false,
                hasCachedRealIdr = false,
            ),
        )
    }

    @Test
    fun `media decoder routes unrecoverable surface fallback to session restart`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/video/MediaCodecDecoder.kt",
        ).readText()
        val attach = source.substringAfter("override fun attach(surface: Surface")
            .substringBefore("override fun detach()")

        assertTrue(attach.contains("SurfaceRecoveryPolicy.afterSwap("))
        assertTrue(attach.contains("hasCachedRealIdr = cachedIdrFrame != null"))
        assertTrue(attach.contains("SurfaceRecoveryAction.RESTART_SESSION"))
        assertTrue(attach.contains("onSessionRestartNeeded("))
    }

    @Test
    fun `session manager performs surface recovery restart off the main thread`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
        ).readText()

        assertTrue(source.contains("onSessionRestartNeeded = { reason ->"))
        assertTrue(source.contains("scope.launch(kotlinx.coroutines.Dispatchers.IO)"))
        assertTrue(source.contains("targetSession.forceReconnect(reason)"))
    }

    @Test
    fun `stale decoder callback cannot restart a replacement session`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
        ).readText()

        assertTrue(source.contains("val decoderGeneration = videoDecoderGeneration.incrementAndGet()"))
        assertTrue(source.contains("decoderGeneration != videoDecoderGeneration.get()"))
        assertTrue(source.contains("aasdkSession !== targetSession"))
    }

    @Test
    fun `invalid surfaces are never stored or attached`() {
        val manager = projectFile(
            "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
        ).readText()
        val decoder = projectFile(
            "app/src/main/java/com/openautolink/app/video/MediaCodecDecoder.kt",
        ).readText()

        assertTrue(manager.contains("surface?.takeIf { it.isValid"))
        assertTrue(decoder.contains("if (!surface.isValid)"))
    }

    private fun projectFile(path: String): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir unavailable"))
        repeat(8) {
            val candidate = File(dir, path)
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: error("Project root not found for: $path")
        }
        error("Project file not found: $path")
    }
}
