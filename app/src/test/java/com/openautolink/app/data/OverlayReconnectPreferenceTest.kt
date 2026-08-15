package com.openautolink.app.data

import com.openautolink.app.ui.projection.ProjectionUiState
import com.openautolink.app.ui.settings.SettingsUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayReconnectPreferenceTest {

    @Test
    fun `floating reconnect button is off by default in preferences and UI state`() {
        assertFalse(AppPreferences.DEFAULT_OVERLAY_RECONNECT_BUTTON)
        assertFalse(SettingsUiState().overlayReconnectButton)
        assertFalse(ProjectionUiState().overlayReconnectButton)
    }

    @Test
    fun `floating phone switcher preference is represented in both UI states`() {
        assertTrue(AppPreferences.DEFAULT_OVERLAY_PHONE_SWITCH_BUTTON)
        assertTrue(SettingsUiState().overlayPhoneSwitchButton)
        assertTrue(ProjectionUiState().overlayPhoneSwitchButton)
    }
}
