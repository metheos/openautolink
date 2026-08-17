package com.openautolink.app.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class WppProxyPortContractTest {
    @Test
    fun `car advertises the companion reserved loopback port`() {
        assertEquals(5280, OalProtocol.WPP_PROXY_PORT)
    }
}
