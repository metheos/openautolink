package com.openautolink.companion.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.openautolink.companion.diagnostics.CompanionLog
import com.openautolink.companion.diagnostics.PhoneWppDiagnostics
import java.net.Inet4Address

/**
 * Observes Wi-Fi networks for the service lifetime and selects the network that
 * should own the car-facing listeners.
 *
 * This observer is passive: Gearhead or CarWifiManager owns association. The
 * exact CarWifiManager network may be supplied through [prefer]. Otherwise a
 * local-only WifiNetworkSpecifier network observed during an active WPP startup
 * attempt is retained as the WPP candidate; unrelated Wi-Fi is ignored.
 */
class CarNetworkMonitor(
    context: Context,
    private val onSelectedNetworkChanged: (Network?) -> Unit,
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val selector = CarNetworkSelector<Network>()
    private val wppCorrelatedNetworks = mutableSetOf<Network>()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var started = false
    private var publishedNetwork: Network? = null

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh(network)

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = refresh(network, networkCapabilities = networkCapabilities)

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) = refresh(network, linkProperties = linkProperties)

            override fun onLost(network: Network) {
                synchronized(this@CarNetworkMonitor) {
                    if (!started) return
                    wppCorrelatedNetworks.remove(network)
                    selector.lost(network)
                    publishIfChanged()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
            callback = networkCallback
            CompanionLog.d(TAG, "Passive car-network binding observer registered")
        } catch (e: Exception) {
            CompanionLog.w(TAG, "Car-network binding observer unavailable: ${e.message}")
        }
    }

    @Synchronized
    fun prefer(network: Network?) {
        if (!started) return
        if (network != null) refresh(network)
        selector.prefer(network)
        publishIfChanged()
    }

    @Synchronized
    fun stop() {
        started = false
        callback?.let { networkCallback ->
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
            }
        }
        callback = null
        CompanionLog.d(TAG, "Passive car-network binding observer stopped")
    }

    @Synchronized
    private fun refresh(
        network: Network,
        networkCapabilities: NetworkCapabilities? = null,
        linkProperties: LinkProperties? = null,
    ) {
        if (!started) return
        val capabilities = networkCapabilities
            ?: connectivityManager.getNetworkCapabilities(network)
        val properties = linkProperties
            ?: connectivityManager.getLinkProperties(network)
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!isWifi) {
            selector.lost(network)
            publishIfChanged()
            return
        }
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val hasUsableIpv4 = properties?.linkAddresses?.any { linkAddress ->
            val address = linkAddress.address
            address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress
        } == true
        val hasWppSpecifier = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            runCatching {
                capabilities.networkSpecifier is WifiNetworkSpecifier
            }.getOrDefault(false)
        if (PhoneWppDiagnostics.isActive && hasWppSpecifier) {
            wppCorrelatedNetworks += network
        }
        selector.observe(
            network = network,
            hasInternet = hasInternet,
            hasUsableIpv4 = hasUsableIpv4,
            correlatedWithWpp = network in wppCorrelatedNetworks,
        )
        publishIfChanged()
    }

    private fun publishIfChanged() {
        if (!started) return
        val selected = selector.selectedNetwork
        if (selected == publishedNetwork) return
        publishedNetwork = selected
        CompanionLog.i(
            TAG,
            if (selected != null) {
                "Car WiFi network selected for listener binding"
            } else {
                "No car WiFi network selected; listeners use default routing"
            },
        )
        onSelectedNetworkChanged(selected)
    }

    companion object {
        private const val TAG = "OAL_CarNetBind"
    }
}
