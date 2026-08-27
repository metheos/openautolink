package com.openautolink.app.transport

/**
 * One-interface contract for WPP networking.
 *
 * The configured interface is authoritative. A missing interface never degrades
 * to another live NIC, and a peer is usable only when it belongs to the selected
 * interface's current IPv4 subnet using Android's reported prefix length.
 */
object WppInterfacePolicy {
    data class InterfaceIpv4(
        val name: String,
        val address: String,
        val prefixLength: Int = 24,
    )

    fun selectedIpv4(
        selectedInterfaceName: String,
        interfaces: List<InterfaceIpv4>,
    ): String? = interfaces
        .firstOrNull { it.name == selectedInterfaceName && parseIpv4(it.address) != null }
        ?.address

    fun isPeerOnSelectedSubnet(
        selectedLocalIpv4: String,
        peerHost: String,
        prefixLength: Int = livePrefixLength(selectedLocalIpv4) ?: 24,
    ): Boolean {
        if (prefixLength !in 0..32) return false
        val local = parseIpv4(selectedLocalIpv4) ?: return false
        val peer = parseIpv4(stripPort(peerHost)) ?: return false
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        return (toIpv4Int(local) and mask) == (toIpv4Int(peer) and mask)
    }

    fun liveInterfaceIpv4(interfaceName: String): InterfaceIpv4? = runCatching {
        val networkInterface = java.net.NetworkInterface.getByName(interfaceName)
            ?.takeIf { it.isUp && !it.isLoopback && !it.isVirtual }
            ?: return@runCatching null
        networkInterface.interfaceAddresses
            .firstNotNullOfOrNull { interfaceAddress ->
                val address = interfaceAddress.address as? java.net.Inet4Address
                    ?: return@firstNotNullOfOrNull null
                if (address.isLoopbackAddress || address.isLinkLocalAddress) {
                    return@firstNotNullOfOrNull null
                }
                InterfaceIpv4(
                    name = networkInterface.name,
                    address = address.hostAddress ?: return@firstNotNullOfOrNull null,
                    prefixLength = interfaceAddress.networkPrefixLength.toInt(),
                )
            }
    }.getOrNull()

    fun liveIpv4(interfaceName: String): String? = liveInterfaceIpv4(interfaceName)?.address

    private fun livePrefixLength(localIpv4: String): Int? = runCatching {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
        interfaces.toList().firstNotNullOfOrNull { networkInterface ->
            networkInterface.interfaceAddresses.firstNotNullOfOrNull { interfaceAddress ->
                val address = interfaceAddress.address as? java.net.Inet4Address
                    ?: return@firstNotNullOfOrNull null
                if (address.hostAddress == localIpv4) interfaceAddress.networkPrefixLength.toInt()
                else null
            }
        }
    }.getOrNull()

    private fun toIpv4Int(octets: IntArray): Int =
        (octets[0] shl 24) or (octets[1] shl 16) or (octets[2] shl 8) or octets[3]

    private fun stripPort(host: String): String {
        val trimmed = host.trim()
        if (trimmed.count { it == ':' } != 1) return trimmed
        val separator = trimmed.lastIndexOf(':')
        val port = trimmed.substring(separator + 1).toIntOrNull()
        return if (port != null && port in 1..65535) trimmed.substring(0, separator) else trimmed
    }

    private fun parseIpv4(value: String): IntArray? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (index in parts.indices) {
            val octet = parts[index].toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            octets[index] = octet
        }
        return octets
    }
}
