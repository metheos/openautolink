package com.openautolink.app.transport.bluetooth

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WppAdmissionWiringContractTest {

    @Test
    fun `all advertiser entry points require a current WPP owner`() {
        val control = source("app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt")
        val ensure = control.substringAfter("fun ensureAdvertising() {")
            .substringBefore("private val ensureInFlight")
        val start = control.substringAfter("private suspend fun startFromPreferences(")
            .substringBefore("private suspend fun awaitApInterface")
        val publish = control.substringAfter("private fun startAdvertising(")

        assertTrue(ensure.contains("sessionAdmission.currentWppOwner()"))
        assertTrue(ensure.contains("sessionAdmission.isCurrent(owner)"))
        assertTrue(start.contains("!sessionAdmission.isCurrent(expectedOwner)"))
        assertTrue(publish.contains("!sessionAdmission.isCurrent(expectedOwner)"))
        assertTrue(control.contains("if (!sessionAdmission.canAdvertise()) return null"))
        assertTrue(control.contains("expectedOwner: WppSessionAdmission.Token? = null"))
        assertTrue(control.contains("stopAdvertisingNow(\"re-advertise\", expectedOwner = owner)"))
        val install = control.substringAfter("fun installSessionOwner(")
            .substringBefore("fun clearSessionOwner")
        assertTrue(install.contains("synchronized(this)"))
        assertTrue(install.contains("sessionAdmission.installSession(transportMode, wppInterfaceName)"))
        assertTrue(control.contains("val apInterface = expectedOwner.wppInterfaceName"))
    }

    @Test
    fun `initial owner waits and recovery publishes only after its transport is ready`() {
        val session = source("app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt")
        val startWpp = session.substringAfter("private fun startWpp(recovery: Boolean = false)")
            .substringBefore("var onNativeSessionStarting")

        assertTrue(startWpp.indexOf("if (recovery)") < startWpp.indexOf("withIdentityValidatedCompanion"))
        assertTrue(startWpp.contains("Initial WPP owner installed"))
        val knownBlock = startWpp.substringAfter("if (known != null)")
            .substringBefore("return")
        val boundBlock = startWpp.substringAfter("onBound = {")
            .substringBefore("bindInterfaceName =")
        assertTrue(knownBlock.contains("AaWirelessBtControl.readvertise()"))
        assertTrue(boundBlock.contains("if (recovery)"))
        assertTrue(boundBlock.contains("AaWirelessBtControl.readvertise()"))
        assertEquals(2, Regex("""AaWirelessBtControl\.readvertise\(\)""").findAll(startWpp).count())
    }

    @Test
    fun `live projection rejects a redundant activation before handshake messages`() {
        val control = source("app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt")
        val begin = control.substringAfter("fun beginHandshake(): HandshakeLease?")
            .substringBefore("private fun endHandshake")
        val server = source("app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtServer.kt")

        assertTrue(begin.contains("if (sessionIsStreaming?.invoke() == true)"))
        assertTrue(begin.contains("Suppressing redundant WPP activation"))
        assertTrue(server.contains("Rejecting dial-back — handshake admission denied"))
    }

    private fun source(relativePath: String): String {
        val workingDir = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDir) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate $relativePath")
    }
}
