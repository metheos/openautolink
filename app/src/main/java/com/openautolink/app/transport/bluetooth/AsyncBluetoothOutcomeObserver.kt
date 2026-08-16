package com.openautolink.app.transport.bluetooth

/** Dispatches diagnostic observers away from the Bluetooth accept/handshake path. */
class AsyncBluetoothOutcomeObserver(
    private val enqueue: (() -> Unit) -> Unit,
    private val onSdpPublished: (Long) -> Unit,
    private val onPhoneDialback: (Long) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    fun sdpPublishedAt(elapsedMs: Long) {
        enqueue { runCatching { onSdpPublished(elapsedMs) }.onFailure(onFailure) }
    }

    fun phoneDialbackAt(elapsedMs: Long) {
        enqueue { runCatching { onPhoneDialback(elapsedMs) }.onFailure(onFailure) }
    }
}
