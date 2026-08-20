package com.openautolink.app.transport.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WppSessionAdmissionTest {

    @Test
    fun `selected WPP preference cannot advertise through an old USB owner`() {
        val admission = WppSessionAdmission()
        val usb = admission.installSession("usb")

        assertFalse(admission.canAdvertise())
        assertTrue(admission.clearSession(usb))
    }

    @Test
    fun `WPP owner enables advertisement only after installation`() {
        val admission = WppSessionAdmission()

        assertFalse(admission.canAdvertise())
        val wpp = admission.installSession("wpp")
        assertTrue(admission.canAdvertise())
        assertTrue(admission.clearSession(wpp))
        assertFalse(admission.canAdvertise())
    }

    @Test
    fun `stale teardown cannot clear a replacement WPP owner`() {
        val admission = WppSessionAdmission()
        val old = admission.installSession("wpp")
        val replacement = admission.installSession("wpp")

        assertFalse(admission.clearSession(old))
        assertTrue(admission.canAdvertise())
        assertTrue(admission.clearSession(replacement))
        assertFalse(admission.canAdvertise())
    }
}
