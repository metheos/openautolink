package com.openautolink.app.transport.aasdk

import com.openautolink.app.transport.AudioPurpose
import com.openautolink.app.transport.ControlMessage
import java.util.ArrayDeque

/**
 * Bounded ordered mailbox for peer-driven audio lifecycle callbacks.
 *
 * Global arrival order is preserved across purposes. For each of the five
 * purposes, only the latest two transitions are retained, capping the mailbox
 * at ten entries while preserving the latest Stop -> Start or Start -> Stop.
 */
internal class AudioLifecycleMailbox(
    private val maxPerPurpose: Int = 2,
) {
    private data class Entry(
        val purpose: AudioPurpose,
        val message: ControlMessage,
    )

    private val lock = Any()
    private val queue = ArrayDeque<Entry>()

    val size: Int
        get() = synchronized(lock) { queue.size }

    fun offer(message: ControlMessage) {
        val purpose = message.audioPurpose() ?: return
        synchronized(lock) {
            // A repeated state supersedes its older payload (for example, a
            // newer Start format) and belongs at its actual arrival position.
            val reverse = queue.descendingIterator()
            while (reverse.hasNext()) {
                val pending = reverse.next()
                if (pending.purpose == purpose && pending.message.javaClass == message.javaClass) {
                    reverse.remove()
                    break
                }
            }

            queue.addLast(Entry(purpose, message))
            while (queue.count { it.purpose == purpose } > maxPerPurpose) {
                val forward = queue.iterator()
                while (forward.hasNext()) {
                    if (forward.next().purpose == purpose) {
                        forward.remove()
                        break
                    }
                }
            }
        }
    }

    fun poll(): ControlMessage? = synchronized(lock) {
        queue.pollFirst()?.message
    }

    fun offerFirst(message: ControlMessage): Boolean {
        val purpose = message.audioPurpose() ?: return false
        return synchronized(lock) {
            queue.addFirst(Entry(purpose, message))
            while (queue.count { it.purpose == purpose } > maxPerPurpose) {
                val forward = queue.iterator()
                while (forward.hasNext()) {
                    if (forward.next().purpose == purpose) {
                        forward.remove()
                        break
                    }
                }
            }
            queue.any { it.message === message }
        }
    }

    fun remove(message: ControlMessage): Boolean = synchronized(lock) {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().message === message) {
                iterator.remove()
                return@synchronized true
            }
        }
        false
    }

    fun clear() = synchronized(lock) {
        queue.clear()
    }

    private fun ControlMessage.audioPurpose(): AudioPurpose? = when (this) {
        is ControlMessage.AudioStart -> purpose
        is ControlMessage.AudioStop -> purpose
        else -> null
    }
}
