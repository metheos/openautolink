package com.openautolink.app.transport.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTransportOwnershipTest {

    @Test
    fun `unrelated USB detach cannot retire the active phone transport`() {
        val ownership = UsbTransportOwnership()
        val active = ownership.claim("/dev/bus/usb/002/024")

        assertNull(ownership.detach("/dev/bus/usb/001/003"))
        assertTrue(ownership.isCurrent(active))
    }

    @Test
    fun `active accessory detach returns its token exactly once`() {
        val ownership = UsbTransportOwnership()
        val active = ownership.claim("/dev/bus/usb/002/024")

        assertEquals(active, ownership.detach("/dev/bus/usb/002/024"))
        assertFalse(ownership.isCurrent(active))
        assertNull(ownership.detach("/dev/bus/usb/002/024"))
    }

    @Test
    fun `stale detach cannot retire a reenumerated replacement`() {
        val ownership = UsbTransportOwnership()
        val old = ownership.claim("/dev/bus/usb/002/023")
        val replacement = ownership.claim("/dev/bus/usb/002/024")

        assertNull(ownership.detach(old.deviceName))
        assertTrue(ownership.isCurrent(replacement))
        assertEquals(replacement, ownership.detach(replacement.deviceName))
    }
}
