package com.openautolink.app.media

import android.view.KeyEvent

/** Pure policy for built-in GM MediaSession controls and mute synchronization. */
object GmMediaControlPolicy {
    enum class Command {
        PLAY,
        PAUSE,
        STOP,
        NEXT,
        PREVIOUS,
    }

    fun keyCodeFor(command: Command): Int = when (command) {
        Command.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
        Command.PAUSE,
        Command.STOP -> KeyEvent.KEYCODE_MEDIA_PAUSE
        Command.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        Command.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
    }

    fun effectiveMuted(masterMuted: Boolean, streamMuted: Boolean): Boolean =
        masterMuted || streamMuted

    fun nextMuteState(
        masterMuted: Boolean,
        streamMuted: Boolean,
        lastDeliveredMuted: Boolean?,
        retainedMuted: Boolean?,
    ): Boolean? {
        val effectiveMuted = effectiveMuted(masterMuted, streamMuted)
        if (lastDeliveredMuted == null) {
            return when {
                effectiveMuted -> true
                retainedMuted == true -> false
                else -> null
            }
        }
        return effectiveMuted.takeIf { it != lastDeliveredMuted }
    }
}
