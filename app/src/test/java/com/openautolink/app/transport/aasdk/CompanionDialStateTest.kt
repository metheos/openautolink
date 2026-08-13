package com.openautolink.app.transport.aasdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionDialStateTest {

    @Test
    fun startTcpTargetProtectsLiveSessionFromSameIpRedial() {
        val state = CompanionDialState()

        state.recordStartTcpTarget("10.19.238.82")

        assertTrue(state.shouldIgnoreRedial("10.19.238.82", hasLiveTransport = true))
    }

    @Test
    fun differentPhoneCanReplaceLiveSession() {
        val state = CompanionDialState()
        state.recordStartTcpTarget("10.19.238.82")

        assertFalse(state.shouldIgnoreRedial("10.19.238.109", hasLiveTransport = true))
    }

    @Test
    fun deadSessionDoesNotBlockRetryToSamePhone() {
        val state = CompanionDialState()
        state.recordStartTcpTarget("10.19.238.82")

        assertFalse(state.shouldIgnoreRedial("10.19.238.82", hasLiveTransport = false))
    }
}
