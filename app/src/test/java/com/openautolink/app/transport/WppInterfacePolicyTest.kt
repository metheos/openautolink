package com.openautolink.app.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WppInterfacePolicyTest {

    private val interfaces = listOf(
        WppInterfacePolicy.InterfaceIpv4("wlan0", "10.93.118.188"),
        WppInterfacePolicy.InterfaceIpv4("ap_br_swlan0", "10.205.97.238"),
    )

    @Test
    fun `configured interface resolves only its own live IPv4`() {
        assertEquals(
            "10.205.97.238",
            WppInterfacePolicy.selectedIpv4("ap_br_swlan0", interfaces),
        )
    }

    @Test
    fun `missing configured interface never falls back to another interface`() {
        assertNull(WppInterfacePolicy.selectedIpv4("missing0", interfaces))
    }

    @Test
    fun `peer on selected interface subnet is accepted`() {
        assertTrue(
            WppInterfacePolicy.isPeerOnSelectedSubnet(
                selectedLocalIpv4 = "10.205.97.238",
                peerHost = "10.205.97.109:5277",
            ),
        )
    }

    @Test
    fun `peer on another live interface subnet is rejected`() {
        assertFalse(
            WppInterfacePolicy.isPeerOnSelectedSubnet(
                selectedLocalIpv4 = "10.205.97.238",
                peerHost = "10.93.118.184",
            ),
        )
    }

    @Test
    fun `actual prefix admits adjacent slash 24 inside slash 23`() {
        assertTrue(
            WppInterfacePolicy.isPeerOnSelectedSubnet(
                selectedLocalIpv4 = "192.168.1.174",
                peerHost = "192.168.0.35",
                prefixLength = 23,
            ),
        )
        assertFalse(
            WppInterfacePolicy.isPeerOnSelectedSubnet(
                selectedLocalIpv4 = "192.168.1.174",
                peerHost = "192.168.0.35",
                prefixLength = 24,
            ),
        )
    }

    @Test
    fun `malformed and IPv6 peers are rejected`() {
        assertFalse(WppInterfacePolicy.isPeerOnSelectedSubnet("10.205.97.238", "not-an-ip"))
        assertFalse(WppInterfacePolicy.isPeerOnSelectedSubnet("10.205.97.238", "fe80::1"))
    }
}
