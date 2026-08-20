package com.openautolink.app.transport.aasdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalProtocolPolicyTest {

    @Test
    fun `legacy mode requests GAL 1_7 and keeps media acknowledgements`() {
        val config = GalProtocolPolicy.forExperimentalGal6(enabled = false)

        assertEquals(GalProtocolVersion(1, 7), config.requestedVersion)
        assertEquals(30, config.maxUnacked)
        assertTrue(config.sendAudioAcks)
        assertEquals(60, config.hevcKeyframeIntervalSeconds)
    }

    @Test
    fun `experimental mode requests GAL 6_0 and makes audio ackless without changing setup window`() {
        val config = GalProtocolPolicy.forExperimentalGal6(enabled = true)

        assertEquals(GalProtocolVersion(6, 0), config.requestedVersion)
        assertEquals(30, config.maxUnacked)
        assertFalse(config.sendAudioAcks)
        assertEquals(2, config.hevcKeyframeIntervalSeconds)
    }

    @Test
    fun `expected HEVC GOP uses negotiated encoder policy and advertised fps`() {
        assertEquals(3_600, GalProtocolPolicy.expectedHevcGopFrames(experimentalGal6 = false, advertisedFps = 60))
        assertEquals(1_800, GalProtocolPolicy.expectedHevcGopFrames(experimentalGal6 = false, advertisedFps = 30))
        assertEquals(120, GalProtocolPolicy.expectedHevcGopFrames(experimentalGal6 = true, advertisedFps = 60))
        assertEquals(60, GalProtocolPolicy.expectedHevcGopFrames(experimentalGal6 = true, advertisedFps = 30))
    }
}
