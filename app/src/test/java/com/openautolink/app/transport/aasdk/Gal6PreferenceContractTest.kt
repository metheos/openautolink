package com.openautolink.app.transport.aasdk

import com.openautolink.app.data.AppPreferences
import com.openautolink.app.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gal6PreferenceContractTest {

    @Test
    fun `SDR config materializes tested GAL policy for JNI`() {
        val legacy = AasdkSdrConfig(experimentalGal6 = false)
        assertEquals(1, legacy.requestedGalMajor)
        assertEquals(7, legacy.requestedGalMinor)
        assertEquals(30, legacy.mediaSetupMaxUnacked)
        assertTrue(legacy.sendAudioAcks)
        assertEquals(60, legacy.hevcKeyframeIntervalSeconds)

        val modern = AasdkSdrConfig(experimentalGal6 = true)
        assertEquals(6, modern.requestedGalMajor)
        assertEquals(0, modern.requestedGalMinor)
        assertEquals(30, modern.mediaSetupMaxUnacked)
        assertFalse(modern.sendAudioAcks)
        assertEquals(2, modern.hevcKeyframeIntervalSeconds)
    }

    @Test
    fun `experimental GAL 6 mode is off by default through every user-facing layer`() {
        assertFalse(AppPreferences.DEFAULT_EXPERIMENTAL_GAL6)
        assertFalse(SettingsUiState().experimentalGal6)
        assertFalse(AasdkSdrConfig().experimentalGal6)
    }
}
