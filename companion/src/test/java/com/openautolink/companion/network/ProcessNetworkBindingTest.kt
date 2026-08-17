package com.openautolink.companion.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProcessNetworkBindingTest {

    @Test
    fun `temporary process binding is restored after socket creation`() {
        var current: String? = null
        val transitions = mutableListOf<String?>()
        val binding = ProcessNetworkBinding(
            currentNetwork = { current },
            bindProcess = { network ->
                transitions += network
                current = network
                true
            },
            warning = {},
        )

        val result = binding.withNetwork("car") {
            assertEquals("car", current)
            "created"
        }

        assertEquals("created", result)
        assertEquals(listOf("car", null), transitions)
        assertEquals(null, current)
    }

    @Test
    fun `failed target binding does not create an unscoped listener`() {
        var blockRan = false
        val binding = ProcessNetworkBinding<String>(
            currentNetwork = { null },
            bindProcess = { false },
            warning = {},
        )

        try {
            binding.withNetwork("stale") {
                blockRan = true
            }
            fail("Expected target bind failure")
        } catch (_: IllegalStateException) {
        }

        assertFalse(blockRan)
    }

    @Test
    fun `failed restore and failed clear close the result and fail closed`() {
        var cleaned = false
        val binding = ProcessNetworkBinding<String>(
            currentNetwork = { "home" },
            bindProcess = { network -> network == "car" },
            warning = {},
        )

        try {
            binding.withNetwork(
                targetNetwork = "car",
                onUnrestored = { cleaned = true },
            ) { "created" }
            fail("Expected process restore failure")
        } catch (_: ProcessNetworkRestoreException) {
        }

        assertTrue(cleaned)
    }

    @Test
    fun `restore exception is contained and process binding is cleared`() {
        var current: String? = "home"
        val transitions = mutableListOf<String?>()
        val warnings = mutableListOf<String>()
        val binding = ProcessNetworkBinding(
            currentNetwork = { current },
            bindProcess = { network ->
                transitions += network
                when (network) {
                    "car" -> {
                        current = network
                        true
                    }
                    "home" -> throw SecurityException("restore denied")
                    null -> {
                        current = null
                        true
                    }
                    else -> false
                }
            },
            warning = warnings::add,
        )

        val result = binding.withNetwork("car") { "created" }

        assertEquals("created", result)
        assertEquals(listOf("car", "home", null), transitions)
        assertEquals(null, current)
        assertTrue(warnings.single().contains("restore denied"))
        assertTrue(warnings.single().contains("cleared=true"))
    }

    @Test
    fun `failed restoration clears process binding and reports both outcomes`() {
        var current: String? = "home"
        val transitions = mutableListOf<String?>()
        val warnings = mutableListOf<String>()
        val binding = ProcessNetworkBinding(
            currentNetwork = { current },
            bindProcess = { network ->
                transitions += network
                when (network) {
                    "car" -> {
                        current = network
                        true
                    }
                    "home" -> false
                    null -> {
                        current = null
                        true
                    }
                    else -> false
                }
            },
            warning = warnings::add,
        )

        binding.withNetwork("car") { Unit }

        assertEquals(listOf("car", "home", null), transitions)
        assertEquals(null, current)
        assertTrue(warnings.single().contains("restore"))
        assertTrue(warnings.single().contains("cleared=true"))
    }
}

class CarNetworkBindingIntegrationTest {

    @Test
    fun `service forwards exact companion network without restarting advertiser`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/service/CompanionService.kt",
        ).readText()
        val carWifiFunction = source.substringAfter("private fun startCarWifiIfConfigured()")
            .substringBefore("fun restartCarWifi()")

        assertTrue(carWifiFunction.contains("mgr.carNetwork.collect"))
        assertTrue(carWifiFunction.contains("tcpAdvertiser?.updateCarNetwork(network)"))
        assertFalse(carWifiFunction.contains("tcpAdvertiser?.stop()"))
    }

    @Test
    fun `listener rebind leaves proxy bridge and recovery ownership untouched`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/service/TcpAdvertiser.kt",
        ).readText()
        val rebind = source.substringAfter("private fun replaceCarFacingListeners(")
            .substringBefore("private fun closeCarFacingListeners(")

        assertTrue(rebind.contains("listenerGenerations.replaceWith"))
        assertFalse(rebind.contains("activeProxy?.stop()"))
        assertFalse(rebind.contains("activeCarSocket?.close()"))
        assertFalse(rebind.contains("scope.cancel()"))
    }

    @Test
    fun `failed replacement attempts one rollback to the previous listener network`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/service/TcpAdvertiser.kt",
        ).readText()
        val rebind = source.substringAfter("private fun replaceCarFacingListeners(")
            .substringBefore("private fun closeCarFacingListeners(")

        assertTrue(
            rebind.contains(
                "rollbackOnFailure && e !is ProcessNetworkRestoreException",
            ),
        )
        assertTrue(
            rebind.contains(
                "replaceCarFacingListeners(ticket.previousTarget, rollbackOnFailure = false)",
            ),
        )
    }

    @Test
    fun `accepted work is rechecked against its listener generation`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/service/TcpAdvertiser.kt",
        ).readText()
        val mainLoop = source.substringAfter("private fun startMainServer(")
            .substringBefore("private data class CarFacingListeners")
        val identityLoop = source.substringAfter("private fun startIdentityServer(")
            .substringBefore("private fun respondIdentityProbe(")
        val udpLoop = source.substringAfter("private fun startUdpDiscoveryServer(")
            .substringBefore("private fun handleCarConnection(")

        assertTrue(
            mainLoop.substringAfter("val carSocket = server.accept()")
                .substringBefore("PhoneWppDiagnostics.record(PhoneWppStage.CAR_SOCKET)")
                .contains("listenerGenerations.owns(ticket)"),
        )
        assertTrue(
            identityLoop.substringAfter("val client = server.accept()")
                .substringBefore("val remoteIp")
                .contains("listenerGenerations.owns(ticket)"),
        )
        assertTrue(
            udpLoop.substringAfter("socket.receive(packet)")
                .substringBefore("val remoteIp")
                .contains("listenerGenerations.owns(ticket)"),
        )
    }

    @Test
    fun `stale CarWifiManager callbacks cannot clear a newer exact network`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/wifi/CarWifiManager.kt",
        ).readText()
        val callback = source.substringAfter("val callback = object : ConnectivityManager.NetworkCallback()")
            .substringBefore("currentCallback = callback")

        assertTrue(source.contains("val generation = requestGeneration"))
        assertEquals(3, callback.windowed("if (!ownsRequest(generation)) return".length)
            .count { it == "if (!ownsRequest(generation)) return" })
    }

    @Test
    fun `other outbound sockets choose an explicit default network outside bind window`() {
        val uploader = projectFile(
            "companion/src/main/java/com/openautolink/companion/diagnostics/LogUploader.kt",
        ).readText()

        assertTrue(uploader.contains("ProcessNetworkBindingLock.withLock"))
        assertTrue(uploader.contains("defaultNetwork.openConnection(endpoint)"))
    }

    @Test
    fun `wpp network discovery is passive and loopback creation shares binding lock`() {
        val monitor = projectFile(
            "companion/src/main/java/com/openautolink/companion/network/CarNetworkMonitor.kt",
        ).readText()
        val loopback = projectFile(
            "companion/src/main/java/com/openautolink/companion/connection/WppProxySocketBinder.kt",
        ).readText()

        assertTrue(monitor.contains("registerNetworkCallback("))
        assertFalse(monitor.contains("requestNetwork("))
        assertTrue(monitor.contains("capabilities.networkSpecifier is WifiNetworkSpecifier"))
        assertTrue(monitor.contains("PhoneWppDiagnostics.isActive && hasWppSpecifier"))
        assertTrue(loopback.contains("ProcessNetworkBindingLock.withLock"))
    }

    private fun projectFile(relativePath: String): File {
        val workingDir = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDir) { it.parentFile }
            .map { root -> File(root, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Could not locate $relativePath from $workingDir")
    }
}
