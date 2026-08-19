package com.openautolink.app.session

import com.openautolink.app.transport.aasdk.AasdkSdrConfig
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsForwardingContractTest {

    @Test
    fun `service discovery advertises vehicle location by default`() {
        assertTrue(AasdkSdrConfig().gpsForwarding)
    }

    @Test
    fun `service discovery can disable vehicle location`() {
        assertFalse(AasdkSdrConfig(gpsForwarding = false).gpsForwarding)
    }

    @Test
    fun `session manager gates location samples and passes setting to service discovery`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
        ).readText()
        val forwarding = source.substringAfter("private fun startLocationForwarding")
            .substringBefore("private fun stopDirectLocationForwarding")

        assertTrue(forwarding.contains("if (!gpsForwardingEnabled)"))
        assertTrue(forwarding.contains("GPS forwarding disabled by setting"))
        assertTrue(source.contains("gpsForwardingEnabled = gpsForwarding"))
        assertTrue(source.contains("gpsForwarding = gpsForwarding,"))
    }

    @Test
    fun `native service discovery omits location capability when forwarding is off`() {
        val source = projectFile("app/src/main/cpp/jni_session.cpp").readText()
        val sensorBlock = source.substringAfter("// ---- Sensor channel ----")
            .substringBefore("// ---- Input channel ----")

        assertTrue(sensorBlock.contains("if (sdrConfig_.gpsForwarding)"))
        assertTrue(sensorBlock.contains("set_sensor_type(ST::SENSOR_LOCATION)"))
    }

    private fun projectFile(relativePath: String): File {
        var current = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate project file: $relativePath")
    }
}
