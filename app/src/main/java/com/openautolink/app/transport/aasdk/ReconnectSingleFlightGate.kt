package com.openautolink.app.transport.aasdk

import java.util.concurrent.atomic.AtomicBoolean

/** Prevents two recovery triggers from tearing down the same native session. */
class ReconnectSingleFlightGate {
    private val running = AtomicBoolean(false)

    fun tryStart(): Boolean = running.compareAndSet(false, true)

    fun finish() {
        running.set(false)
    }
}
