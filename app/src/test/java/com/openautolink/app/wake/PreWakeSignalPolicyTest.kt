package com.openautolink.app.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreWakeSignalPolicyTest {

    private val policy = PreWakeSignalPolicy()

    @Test
    fun `GM states one through eight are recorded with their raw values`() {
        val expectedNames = listOf(
            "ANIMATION_INIT",
            "HMI_INIT",
            "HMI_INACTIVE",
            "START",
            "RUN",
            "PROPULSION",
            "ACCESSORY",
            "LOCAL_INFOTAINMENT",
        )

        expectedNames.forEachIndexed { index, expectedName ->
            val raw = index + 1
            val decision = policy.gmSystemState(raw, elapsedMs = raw.toLong())

            assertEquals(PreWakeObservationKind.GM_SYSTEM_STATE, decision.observation.kind)
            assertTrue(decision.observation.detail.contains("raw=$raw"))
            assertTrue(decision.observation.detail.contains("name=$expectedName"))
            assertEquals(WakeSignal.GM_SYSTEM_STATE, decision.event?.signal)
            assertFalse(decision.impliesSessionReadiness)
            assertFalse(decision.authorizesBehavior)
        }
    }

    @Test
    fun `unknown GM raw values are recorded but imply no readiness`() {
        listOf(0, 9, 20, 37).forEach { raw ->
            val decision = policy.gmSystemState(raw, elapsedMs = 100L + raw)

            assertEquals(PreWakeObservationKind.GM_SYSTEM_STATE, decision.observation.kind)
            assertTrue(decision.observation.detail.contains("raw=$raw"))
            assertTrue(decision.observation.detail.contains("name=UNKNOWN"))
            assertEquals(WakeSignal.GM_SYSTEM_STATE, decision.event?.signal)
            assertFalse(decision.impliesSessionReadiness)
            assertFalse(decision.authorizesBehavior)
        }
    }

    @Test
    fun `Bluetooth STATE_ON is recorded without implying session readiness`() {
        val decision = policy.bluetoothState(
            rawState = PreWakeSignalPolicy.BLUETOOTH_STATE_ON,
            elapsedMs = 200L,
        )

        assertEquals(PreWakeObservationKind.BLUETOOTH_STATE, decision.observation.kind)
        assertEquals(WakeSignal.BLUETOOTH_ON, decision.event?.signal)
        assertTrue(decision.observation.detail.contains("raw=12"))
        assertFalse(decision.impliesSessionReadiness)
        assertFalse(decision.authorizesBehavior)
    }

    @Test
    fun `configured AP observation records only interface and local IP`() {
        val decision = policy.accessPoint(
            interfaceName = "ap_br_swlan0",
            localIpv4 = "10.2.110.1",
            elapsedMs = 300L,
        )

        assertEquals(WakeSignal.AP_PRESENT, decision.event?.signal)
        assertEquals("interface=ap_br_swlan0,ip=10.2.110.1", decision.event?.detail)
        val rendered = decision.observation.detail.lowercase()
        assertFalse(rendered.contains("ssid"))
        assertFalse(rendered.contains("psk"))
        assertFalse(rendered.contains("password"))
        assertFalse(rendered.contains("credential"))
        assertFalse(decision.impliesSessionReadiness)
    }

    @Test
    fun `wake candidate starts a bounded non-hot sampling policy`() {
        val decision = policy.gmSystemState(rawState = 1, elapsedMs = 400L)
        val sampling = decision.sampling

        assertNotNull(sampling)
        sampling!!
        assertEquals(250L, sampling.intervalMs)
        assertEquals(15_000L, sampling.durationMs)
        assertTrue(sampling.durationMs > sampling.intervalMs)
        assertTrue(sampling.maximumSamples in 1..100)
        assertFalse(sampling.intervalMs == 100L)
        assertTrue(sampling.isBounded)
    }

    @Test
    fun `power mode sampling is limited to wake and resume values`() {
        assertNotNull(policy.gmPowerMode(rawMode = 2, elapsedMs = 450L).sampling)
        assertNotNull(policy.gmPowerMode(rawMode = 5, elapsedMs = 451L).sampling)

        listOf(-1, 0, 1, 3, 4, 99).forEach { rawMode ->
            assertNull(policy.gmPowerMode(rawMode, elapsedMs = 500L + rawMode).sampling)
        }
    }

    @Test
    fun `ignition callbacks remain valid without any GM observation`() {
        assertEquals(
            WakeSignal.IGNITION_OFF,
            policy.ignitionState(rawState = 2, elapsedMs = 500L).event?.signal,
        )
        assertEquals(
            WakeSignal.IGNITION_ON,
            policy.ignitionState(rawState = 4, elapsedMs = 600L).event?.signal,
        )
        assertEquals(
            WakeSignal.IGNITION_START,
            policy.ignitionState(rawState = 5, elapsedMs = 700L).event?.signal,
        )
    }

    @Test
    fun `Activity callbacks remain valid without any GM observation`() {
        assertEquals(
            WakeSignal.ACTIVITY_START,
            policy.activity(PreWakeActivityCallback.START, elapsedMs = 800L).event?.signal,
        )
        assertEquals(
            WakeSignal.ACTIVITY_RESUME,
            policy.activity(PreWakeActivityCallback.RESUME, elapsedMs = 900L).event?.signal,
        )
        assertNull(policy.activity(PreWakeActivityCallback.PAUSE, elapsedMs = 1_000L).event)
        assertNull(policy.activity(PreWakeActivityCallback.STOP, elapsedMs = 1_100L).event)
    }

    @Test
    fun `spoofable GM broadcasts are hints only and never authorize behavior`() {
        val hints = listOf(
            policy.gmSystemState(rawState = 5, elapsedMs = 1_200L),
            policy.gmPowerMode(rawMode = 99, elapsedMs = 1_300L),
        )

        hints.forEach { decision ->
            assertTrue(decision.observation.hintOnly)
            assertFalse(decision.impliesSessionReadiness)
            assertFalse(decision.authorizesBehavior)
        }
        assertEquals(PreWakeObservationKind.GM_POWER_MODE, hints[1].observation.kind)
        assertTrue(hints[1].observation.detail.contains("raw=99"))
    }
}
