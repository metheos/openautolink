package com.openautolink.app.transport.bluetooth

/**
 * Token-owned admission for the process-scoped WPP advertiser.
 *
 * A preference can change to WPP while the current protocol owner is still USB.
 * Only an installed WPP session owner may publish SDP. Tokens prevent late
 * teardown from an old session from revoking a replacement owner.
 */
class WppSessionAdmission {
    class Token internal constructor(
        val generation: Long,
        val transportMode: String,
    )

    private val lock = Any()
    private var generation = 0L
    private var active: Token? = null

    fun installSession(transportMode: String): Token = synchronized(lock) {
        Token(++generation, transportMode).also { active = it }
    }

    fun clearSession(token: Token): Boolean = synchronized(lock) {
        if (active !== token) return false
        active = null
        true
    }

    fun currentWppOwner(): Token? = synchronized(lock) {
        active?.takeIf { it.transportMode == "wpp" }
    }

    fun isCurrent(token: Token): Boolean = synchronized(lock) { active === token }

    fun canAdvertise(): Boolean = currentWppOwner() != null
}
