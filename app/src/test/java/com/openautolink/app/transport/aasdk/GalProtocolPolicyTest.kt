package com.openautolink.app.transport.aasdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalProtocolPolicyTest {

    @Test
    fun `supported versions expose the accepted production ladder`() {
        assertEquals(listOf("1.7", "4.3", "5.0", "5.1", "6.0"), GalProtocolPolicy.supportedVersions)
    }

    @Test
    fun `GAL thresholds are cumulative from the raw requested version`() {
        data class Expected(
            val version: String,
            val modernDisplay: Boolean,
            val audioAcks: Boolean,
            val singleCodec: Boolean,
            val activeSessionIds: Boolean,
            val hevcIntervalSeconds: Int,
        )

        val cases = listOf(
            Expected("1.7", false, true, false, false, 60),
            Expected("4.3", true, true, false, true, 60),
            Expected("5.0", true, false, true, true, 60),
            Expected("5.1", true, false, true, true, 60),
            Expected("6.0", true, false, true, true, 2),
        )

        cases.forEach { expected ->
            val config = GalProtocolPolicy.forVersion(expected.version)
            assertEquals(expected.version, config.requestedVersion.toString())
            assertEquals(30, config.maxUnacked)
            assertEquals(expected.modernDisplay, config.requireMinimumCompatibleResponse)
            assertEquals(expected.modernDisplay, config.modernDisplayPolicy)
            assertEquals(expected.audioAcks, config.sendAudioAcks)
            assertEquals(expected.singleCodec, config.singleVideoCodecFamily)
            assertEquals(expected.activeSessionIds, config.useActiveMediaSessionIds)
            assertEquals(expected.hevcIntervalSeconds, config.hevcKeyframeIntervalSeconds)
        }
    }

    @Test
    fun `invalid or future configured versions safely fall back to legacy`() {
        listOf("", " 6.0", "6.0 ", "4.03", "6.1", "garbage").forEach { value ->
            assertEquals(GalProtocolVersion(1, 7), GalProtocolPolicy.forVersion(value).requestedVersion)
        }
    }

    @Test
    fun `persisted version migration preserves legacy users and gives new value precedence`() {
        assertEquals("1.7", GalProtocolPolicy.resolvePersistedVersion(null, null))
        assertEquals("1.7", GalProtocolPolicy.resolvePersistedVersion(null, false))
        assertEquals("6.0", GalProtocolPolicy.resolvePersistedVersion(null, true))
        assertEquals("5.1", GalProtocolPolicy.resolvePersistedVersion("5.1", true))
        assertEquals("1.7", GalProtocolPolicy.resolvePersistedVersion("invalid", false))
        assertEquals("6.0", GalProtocolPolicy.resolvePersistedVersion("invalid", true))
    }

    @Test
    fun `expected HEVC GOP uses requested policy and advertised fps`() {
        assertEquals(3_600, GalProtocolPolicy.expectedHevcGopFrames("1.7", 60))
        assertEquals(1_800, GalProtocolPolicy.expectedHevcGopFrames("5.1", 30))
        assertEquals(120, GalProtocolPolicy.expectedHevcGopFrames("6.0", 60))
        assertEquals(60, GalProtocolPolicy.expectedHevcGopFrames("6.0", 30))
        assertEquals(0, GalProtocolPolicy.expectedHevcGopFrames("6.0", -1))
    }

    @Test
    fun `numeric protocol version ordering does not use floating point`() {
        assertTrue(GalProtocolVersion(4, 3) < GalProtocolVersion(5, 0))
        assertTrue(GalProtocolVersion(5, 1) < GalProtocolVersion(6, 0))
        assertFalse(GalProtocolVersion(6, 0) < GalProtocolVersion(5, 1))
    }
}
