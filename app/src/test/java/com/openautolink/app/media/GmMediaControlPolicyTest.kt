package com.openautolink.app.media

import android.view.KeyEvent
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmMediaControlPolicyTest {

    @Test
    fun `experiment defaults off in storage and Settings state`() {
        assertFalse(AppPreferences.DEFAULT_EXPERIMENTAL_GM_MEDIA_CONTROLS)
        assertFalse(SettingsUiState().experimentalGmMediaControls)
    }

    @Test
    fun `disabled experiment does not translate MediaSession commands`() {
        assertNull(
            GmMediaControlPolicy.keyCodeFor(
                GmMediaControlPolicy.Command.PLAY,
                enabled = false,
            ),
        )
    }

    @Test
    fun `enabled experiment uses the discrete key mapping from GM GAL`() {
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            GmMediaControlPolicy.keyCodeFor(GmMediaControlPolicy.Command.PLAY, enabled = true),
        )
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            GmMediaControlPolicy.keyCodeFor(GmMediaControlPolicy.Command.PAUSE, enabled = true),
        )
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            GmMediaControlPolicy.keyCodeFor(GmMediaControlPolicy.Command.STOP, enabled = true),
        )
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_NEXT,
            GmMediaControlPolicy.keyCodeFor(GmMediaControlPolicy.Command.NEXT, enabled = true),
        )
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            GmMediaControlPolicy.keyCodeFor(GmMediaControlPolicy.Command.PREVIOUS, enabled = true),
        )
    }

    @Test
    fun `master or music-stream mute makes the effective HU state muted`() {
        assertFalse(GmMediaControlPolicy.effectiveMuted(masterMuted = false, streamMuted = false))
        assertTrue(GmMediaControlPolicy.effectiveMuted(masterMuted = true, streamMuted = false))
        assertTrue(GmMediaControlPolicy.effectiveMuted(masterMuted = false, streamMuted = true))
        assertTrue(GmMediaControlPolicy.effectiveMuted(masterMuted = true, streamMuted = true))
    }

    @Test
    fun `mute synchronization emits only enabled state transitions`() {
        assertNull(
            GmMediaControlPolicy.nextMuteState(
                enabled = false,
                masterMuted = true,
                streamMuted = false,
                lastDeliveredMuted = false,
            ),
        )
        assertEquals(
            true,
            GmMediaControlPolicy.nextMuteState(
                enabled = true,
                masterMuted = true,
                streamMuted = false,
                lastDeliveredMuted = false,
            ),
        )
        assertNull(
            GmMediaControlPolicy.nextMuteState(
                enabled = true,
                masterMuted = true,
                streamMuted = true,
                lastDeliveredMuted = true,
            ),
        )
        assertEquals(
            false,
            GmMediaControlPolicy.nextMuteState(
                enabled = true,
                masterMuted = false,
                streamMuted = false,
                lastDeliveredMuted = true,
            ),
        )
        assertNull(
            GmMediaControlPolicy.nextMuteState(
                enabled = true,
                masterMuted = false,
                streamMuted = false,
                lastDeliveredMuted = null,
            ),
        )
        assertEquals(
            true,
            GmMediaControlPolicy.nextMuteState(
                enabled = true,
                masterMuted = true,
                streamMuted = false,
                lastDeliveredMuted = null,
            ),
        )
        assertNull(
            GmMediaControlPolicy.nextMuteState(
                enabled = true,
                masterMuted = true,
                streamMuted = false,
                lastDeliveredMuted = true,
            ),
        )
    }
}
