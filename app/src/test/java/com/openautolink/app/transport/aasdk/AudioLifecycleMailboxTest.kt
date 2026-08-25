package com.openautolink.app.transport.aasdk

import com.openautolink.app.transport.AudioPurpose
import com.openautolink.app.transport.ControlMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLifecycleMailboxTest {

    @Test
    fun `poll preserves lifecycle order across purposes`() {
        val mailbox = AudioLifecycleMailbox()
        val mediaStart = start(AudioPurpose.MEDIA, 48_000, 2)
        val navStart = start(AudioPurpose.NAVIGATION, 16_000, 1)
        val mediaStop = ControlMessage.AudioStop(AudioPurpose.MEDIA)

        mailbox.offer(mediaStart)
        mailbox.offer(navStart)
        mailbox.offer(mediaStop)

        assertEquals(listOf(mediaStart, navStart, mediaStop), drain(mailbox))
    }

    @Test
    fun `mailbox keeps latest transition pair per purpose and remains globally bounded`() {
        val mailbox = AudioLifecycleMailbox()
        for (purpose in AudioPurpose.entries) {
            mailbox.offer(start(purpose, 1, 1))
            mailbox.offer(ControlMessage.AudioStop(purpose))
            mailbox.offer(start(purpose, 2, 1))
        }

        assertTrue(mailbox.size <= AudioPurpose.entries.size * 2)
        val messages = drain(mailbox)
        for (purpose in AudioPurpose.entries) {
            val perPurpose = messages.filter { it.purpose() == purpose }
            assertEquals(2, perPurpose.size)
            assertTrue(perPurpose[0] is ControlMessage.AudioStop)
            assertEquals(start(purpose, 2, 1), perPurpose[1])
        }
    }

    @Test
    fun `new duplicate moves to its actual arrival position`() {
        val mailbox = AudioLifecycleMailbox()
        val oldMediaStart = start(AudioPurpose.MEDIA, 1, 1)
        val navStart = start(AudioPurpose.NAVIGATION, 1, 1)
        val newMediaStart = start(AudioPurpose.MEDIA, 2, 1)

        mailbox.offer(oldMediaStart)
        mailbox.offer(navStart)
        mailbox.offer(newMediaStart)

        assertEquals(listOf(navStart, newMediaStart), drain(mailbox))
    }

    @Test
    fun `remove withdraws only the exact rejected message`() {
        val mailbox = AudioLifecycleMailbox()
        val mediaStop = ControlMessage.AudioStop(AudioPurpose.MEDIA)
        val mediaStart = start(AudioPurpose.MEDIA, 48_000, 2)
        mailbox.offer(mediaStop)
        mailbox.offer(mediaStart)

        assertTrue(mailbox.remove(mediaStart))
        assertEquals(listOf(mediaStop), drain(mailbox))
    }

    @Test
    fun `clear drops every pending lifecycle transition`() {
        val mailbox = AudioLifecycleMailbox()
        mailbox.offer(start(AudioPurpose.MEDIA, 48_000, 2))
        mailbox.offer(ControlMessage.AudioStop(AudioPurpose.NAVIGATION))

        mailbox.clear()

        assertEquals(0, mailbox.size)
        assertEquals(null, mailbox.poll())
    }

    @Test
    fun `rejected publication can be retried at the front without reordering`() {
        val mailbox = AudioLifecycleMailbox()
        val mediaStart = start(AudioPurpose.MEDIA, 48_000, 2)
        val navStart = start(AudioPurpose.NAVIGATION, 16_000, 1)
        mailbox.offer(mediaStart)
        mailbox.offer(navStart)

        val claimed = mailbox.poll()
        mailbox.offerFirst(claimed!!)

        assertEquals(listOf(mediaStart, navStart), drain(mailbox))
    }

    private fun start(purpose: AudioPurpose, rate: Int, channels: Int) =
        ControlMessage.AudioStart(purpose, rate, channels)

    private fun drain(mailbox: AudioLifecycleMailbox): List<ControlMessage> = buildList {
        while (true) add(mailbox.poll() ?: break)
    }

    private fun ControlMessage.purpose(): AudioPurpose = when (this) {
        is ControlMessage.AudioStart -> purpose
        is ControlMessage.AudioStop -> purpose
        else -> error("not an audio lifecycle message")
    }
}
