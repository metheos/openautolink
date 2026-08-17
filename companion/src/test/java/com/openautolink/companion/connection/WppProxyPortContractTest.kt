package com.openautolink.companion.connection

import com.openautolink.companion.service.TcpAdvertiser
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WppProxyPortContractTest {

    @Test
    fun `WPP proxy uses the reserved loopback port`() {
        assertEquals(5280, TcpAdvertiser.WPP_PROXY_PORT)

        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/connection/WppProxySocketBinder.kt",
        ).readText()
        assertTrue(source.contains("InetAddress.getByName(\"127.0.0.1\")"))
        assertTrue(source.contains("TcpAdvertiser.WPP_PROXY_PORT"))
        assertTrue(source.contains("server.bind("))
    }

    @Test
    fun `proxy accepts an IPv4 loopback connection on the reserved port`() {
        val server = WppProxySocketBinder.bind()
        try {
            val port = server.localPort
            assertEquals(TcpAdvertiser.WPP_PROXY_PORT, port)
            Socket().use { client ->
                client.connect(
                    InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
                    1_000,
                )
                assertTrue(client.isConnected)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `failed bind cannot publish an unstarted active proxy`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/service/TcpAdvertiser.kt",
        ).readText()

        val ensure = source.substringAfter("private fun ensureProxyForWpp(): Int")
            .substringBefore("fun preWarmAaPipeline()")
        assertTrue(ensure.indexOf("val port = proxy.start()") < ensure.indexOf("activeProxy = proxy"))

        val prewarm = source.substringAfter("fun preWarmAaPipeline()")
            .substringBefore("private fun startIdentityServer()")
        assertTrue(prewarm.indexOf("val localPort = proxy.start()") < prewarm.indexOf("activeProxy = proxy"))

        val launch = source.substringAfter("private fun launchAndroidAuto(carSocket: Socket)")
            .substringBefore("private fun startAaConnectWatchdog")
        assertTrue(launch.indexOf("val localPort = proxy.start()") < launch.indexOf("activeProxy = proxy"))
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
