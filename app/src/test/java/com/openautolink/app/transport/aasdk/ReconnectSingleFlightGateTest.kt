package com.openautolink.app.transport.aasdk

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectSingleFlightGateTest {

    @Test
    fun `first reconnect enters the gate`() {
        val gate = ReconnectSingleFlightGate()
        assertTrue(gate.tryStart())
    }

    @Test
    fun `overlapping reconnect is rejected`() {
        val gate = ReconnectSingleFlightGate()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
    }

    @Test
    fun `finished reconnect allows a later attempt`() {
        val gate = ReconnectSingleFlightGate()
        assertTrue(gate.tryStart())
        gate.finish()
        assertTrue(gate.tryStart())
    }

    @Test
    fun `force reconnect is guarded and releases after the teardown window`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt",
        ).readText()
        val function = source.substringAfter("fun forceReconnect(reason: String)")
            .substringBefore("// -- Input forwarding")

        assertTrue(function.contains("if (!forceReconnectGate.tryStart())"))
        assertTrue(function.contains("delay(FORCE_RECONNECT_GUARD_MS)"))
        assertTrue(function.contains("forceReconnectGate.finish()"))
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
