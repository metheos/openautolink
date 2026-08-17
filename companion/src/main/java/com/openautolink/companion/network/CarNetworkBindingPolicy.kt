package com.openautolink.companion.network

/**
 * Selects the Wi-Fi network that owns the car-facing listeners.
 *
 * A companion-requested network is authoritative because it came from the exact
 * WifiNetworkSpecifier callback. Without that signal, WPP-owned association is
 * accepted only after a local-only Wi-Fi network has been correlated with an
 * active WPP startup attempt. Ordinary internet and unrelated local Wi-Fi are
 * never guessed to be the car network.
 */
class CarNetworkSelector<T> {
    private data class Candidate(
        val hasInternet: Boolean,
        val hasUsableIpv4: Boolean,
        val correlatedWithWpp: Boolean,
    )

    private val candidates = linkedMapOf<T, Candidate>()
    private var preferredNetwork: T? = null

    var selectedNetwork: T? = null
        private set

    fun observe(
        network: T,
        hasInternet: Boolean,
        hasUsableIpv4: Boolean,
        correlatedWithWpp: Boolean,
    ) {
        candidates[network] = Candidate(
            hasInternet = hasInternet,
            hasUsableIpv4 = hasUsableIpv4,
            correlatedWithWpp = correlatedWithWpp,
        )
        reconcile()
    }

    fun lost(network: T) {
        candidates.remove(network)
        reconcile()
    }

    fun prefer(network: T?) {
        preferredNetwork = network
        reconcile()
    }

    private fun reconcile() {
        val preferred = preferredNetwork
        if (preferred != null && candidates[preferred]?.hasUsableIpv4 == true) {
            selectedNetwork = preferred
            return
        }

        val current = selectedNetwork
        if (current != null && candidates[current]?.isInferredCarNetwork == true) {
            return
        }

        selectedNetwork = candidates.entries
            .firstOrNull { it.value.isInferredCarNetwork }
            ?.key
    }

    private val Candidate.isInferredCarNetwork: Boolean
        get() = correlatedWithWpp && hasUsableIpv4 && !hasInternet
}

/** Generation token for asynchronous listener replacement. */
data class ListenerBindingTicket<T>(
    val generation: Long,
    val target: T?,
    val previousTarget: T?,
)

/**
 * Makes listener replacement idempotent and prevents stale bind completions from
 * publishing sockets after a newer network transition or final stop.
 */
class ListenerBindingGenerations<T> {
    private var generation = 0L
    private var initialized = false
    private var target: T? = null

    @Synchronized
    fun replaceWith(newTarget: T?): ListenerBindingTicket<T>? {
        if (initialized && newTarget == target) return null
        val previousTarget = target
        initialized = true
        target = newTarget
        generation += 1L
        return ListenerBindingTicket(generation, newTarget, previousTarget)
    }

    @Synchronized
    fun owns(ticket: ListenerBindingTicket<T>): Boolean =
        ticket.generation == generation && ticket.target == target

    @Synchronized
    fun stop() {
        generation += 1L
        target = null
    }
}
