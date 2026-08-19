package com.openautolink.app.transport.aasdk

/**
 * Orders transport teardown so the native read thread cannot be joined while it
 * is still blocked in [AasdkTransportPipe.readBytes].
 */
internal object NativeTransportTeardown {
    fun closePipeBeforeNativeStop(
        pipe: AasdkTransportPipe?,
        nativeStop: () -> Unit,
    ): AasdkTransportPipe? {
        pipe?.close()
        nativeStop()
        return null
    }
}
