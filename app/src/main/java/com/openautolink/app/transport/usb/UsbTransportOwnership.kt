package com.openautolink.app.transport.usb

/** Token-owned identity for the one USB accessory transport allowed to feed JNI. */
class UsbTransportOwnership {
    class Token internal constructor(
        val generation: Long,
        val deviceName: String,
    )

    private val lock = Any()
    private var generation = 0L
    private var active: Token? = null

    fun claim(deviceName: String): Token = synchronized(lock) {
        Token(++generation, deviceName).also { active = it }
    }

    fun detach(deviceName: String): Token? = synchronized(lock) {
        val current = active ?: return null
        if (current.deviceName != deviceName) return null
        active = null
        current
    }

    fun isCurrent(token: Token): Boolean = synchronized(lock) { active === token }

    fun clear() = synchronized(lock) { active = null }
}
