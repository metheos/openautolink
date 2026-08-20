package com.openautolink.app.transport.aasdk

import com.openautolink.app.data.AppPreferences
import com.openautolink.app.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gal6PreferenceContractTest {

    @Test
    fun `SDR config materializes every tested GAL policy for JNI`() {
        GalProtocolPolicy.supportedVersions.forEach { version ->
            val expected = GalProtocolPolicy.forVersion(version)
            val actual = AasdkSdrConfig(galVersion = version)

            assertEquals(expected.requestedVersion.major, actual.requestedGalMajor)
            assertEquals(expected.requestedVersion.minor, actual.requestedGalMinor)
            assertEquals(30, actual.mediaSetupMaxUnacked)
            assertEquals(expected.sendAudioAcks, actual.sendAudioAcks)
            assertEquals(expected.requireMinimumCompatibleResponse, actual.requireMinimumCompatibleResponse)
            assertEquals(expected.modernDisplayPolicy, actual.modernDisplayPolicy)
            assertEquals(expected.singleVideoCodecFamily, actual.singleVideoCodecFamily)
            assertEquals(expected.useActiveMediaSessionIds, actual.useActiveMediaSessionIds)
            assertEquals(expected.hevcKeyframeIntervalSeconds, actual.hevcKeyframeIntervalSeconds)
        }
    }

    @Test
    fun `GAL defaults remain legacy until explicitly selected`() {
        assertEquals("1.7", AppPreferences.DEFAULT_GAL_VERSION)
        assertEquals("1.7", SettingsUiState().galVersion)
        assertEquals("1.7", AasdkSdrConfig().galVersion)
        assertTrue(AasdkSdrConfig().sendAudioAcks)
        assertFalse(AasdkSdrConfig().modernDisplayPolicy)
    }
}
