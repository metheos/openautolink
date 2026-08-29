package com.openautolink.app.media

import android.view.KeyEvent

/** Pure policy for the opt-in GM MediaSession control experiment. */
object GmMediaControlPolicy {
    enum class Command {
        PLAY,
        PAUSE,
        STOP,
        NEXT,
        PREVIOUS,
    }

    fun keyCodeFor(command: Command, enabled: Boolean): Int? {
        if (!enabled) return null
        return when (command) {
            Command.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            Command.PAUSE,
            Command.STOP -> KeyEvent.KEYCODE_MEDIA_PAUSE
            Command.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            Command.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
    }

    fun effectiveMuted(masterMuted: Boolean, streamMuted: Boolean): Boolean =
        masterMuted || streamMuted

    fun nextMuteState(
        enabled: Boolean,
        masterMuted: Boolean,
        streamMuted: Boolean,
        lastDeliveredMuted: Boolean?,
    ): Boolean? {
        if (!enabled) return null
        val effectiveMuted = effectiveMuted(masterMuted, streamMuted)
        if (lastDeliveredMuted == null) return true.takeIf { effectiveMuted }
        return effectiveMuted.takeIf { it != lastDeliveredMuted }
    }
}
