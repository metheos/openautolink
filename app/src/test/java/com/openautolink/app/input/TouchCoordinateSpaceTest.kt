package com.openautolink.app.input

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchCoordinateSpaceTest {
    @Test
    fun `4K video inner rect is normalized into advertised 1440p touch space`() {
        val result = TouchCoordinateSpace.innerForProtocol(
            protocolWidth = 2560,
            protocolHeight = 1440,
            videoWidth = 3840,
            videoHeight = 2160,
            videoInnerWidth = 3840,
            videoInnerHeight = 1493,
        )

        assertEquals(2560, result.first)
        assertEquals(995, result.second)
    }

    @Test
    fun `matching video and touch dimensions preserve the inner rect`() {
        val result = TouchCoordinateSpace.innerForProtocol(
            protocolWidth = 2560,
            protocolHeight = 1440,
            videoWidth = 2560,
            videoHeight = 1440,
            videoInnerWidth = 2560,
            videoInnerHeight = 995,
        )

        assertEquals(2560, result.first)
        assertEquals(995, result.second)
    }

    @Test
    fun `invalid video dimensions fall back to full protocol space`() {
        assertEquals(
            2560 to 1440,
            TouchCoordinateSpace.innerForProtocol(2560, 1440, 0, 0, 0, 0),
        )
    }
}
