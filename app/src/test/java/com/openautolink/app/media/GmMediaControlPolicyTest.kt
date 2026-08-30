package com.openautolink.app.media

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmMediaControlPolicyTest {

    @Test
    fun `built-in controls use the discrete key mapping from GM GAL`() {
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            GmMediaControlPolicy.keyCodeFor(GmMediaControlPolicy.Command.PLAY),
        )
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            GmMediaControlPolicy.keyCodeFor(GmMediaControlPolicy.Command.PAUSE),
        )
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            GmMediaControlPolicy.keyCodeFor(GmMediaControlPolicy.Command.STOP),
        )
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_NEXT,
            GmMediaControlPolicy.keyCodeFor(GmMediaControlPolicy.Command.NEXT),
        )
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            GmMediaControlPolicy.keyCodeFor(GmMediaControlPolicy.Command.PREVIOUS),
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
    fun `mute synchronization emits only state transitions`() {
        assertEquals(
            true,
            GmMediaControlPolicy.nextMuteState(
                masterMuted = true,
                streamMuted = false,
                lastDeliveredMuted = false,
                retainedMuted = false,
            ),
        )
        assertNull(
            GmMediaControlPolicy.nextMuteState(
                masterMuted = true,
                streamMuted = true,
                lastDeliveredMuted = true,
                retainedMuted = true,
            ),
        )
        assertEquals(
            false,
            GmMediaControlPolicy.nextMuteState(
                masterMuted = false,
                streamMuted = false,
                lastDeliveredMuted = true,
                retainedMuted = true,
            ),
        )
        assertNull(
            GmMediaControlPolicy.nextMuteState(
                masterMuted = false,
                streamMuted = false,
                lastDeliveredMuted = null,
                retainedMuted = false,
            ),
        )
        assertEquals(
            true,
            GmMediaControlPolicy.nextMuteState(
                masterMuted = true,
                streamMuted = false,
                lastDeliveredMuted = null,
                retainedMuted = false,
            ),
        )
        assertNull(
            GmMediaControlPolicy.nextMuteState(
                masterMuted = true,
                streamMuted = false,
                lastDeliveredMuted = true,
                retainedMuted = true,
            ),
        )
    }

    @Test
    fun `startup unmute corrects a retained mute without creating an initial transition`() {
        assertEquals(
            false,
            GmMediaControlPolicy.nextMuteState(
                masterMuted = false,
                streamMuted = false,
                lastDeliveredMuted = null,
                retainedMuted = true,
            ),
        )
        assertNull(
            GmMediaControlPolicy.nextMuteState(
                masterMuted = false,
                streamMuted = false,
                lastDeliveredMuted = null,
                retainedMuted = false,
            ),
        )
    }
}
