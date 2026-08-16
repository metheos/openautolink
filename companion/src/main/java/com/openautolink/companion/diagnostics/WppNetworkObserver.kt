package com.openautolink.companion.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/** Passive Wi-Fi presence observer for the active diagnostic attempt. */
class WppNetworkObserver(
    context: Context,
    private val onStage: (PhoneWppStage) -> Unit,
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val addressedNetworks = WppObservedNetworkSet<Network>()
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start() {
        if (callback != null) return
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivityManager.getLinkProperties(network)?.let { properties ->
                    observeAddressedWifi(network, properties)
                }
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                observeAddressedWifi(network, properties)
            }

            override fun onLost(network: Network) {
                val wasAddressed = synchronized(this@WppNetworkObserver) {
                    addressedNetworks.lost(network)
                }
                if (wasAddressed) onStage(PhoneWppStage.NETWORK_LOST)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
            callback = networkCallback
            CompanionLog.d(TAG, "Passive WiFi network observer registered")
        } catch (e: Exception) {
            CompanionLog.w(TAG, "Passive WiFi network observer unavailable: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        callback?.let { networkCallback ->
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
            }
        }
        callback = null
        addressedNetworks.clear()
        CompanionLog.d(TAG, "Passive WiFi network observer stopped")
    }

    private fun observeAddressedWifi(network: Network, properties: LinkProperties) {
        if (properties.interfaceName.isNullOrBlank() || properties.linkAddresses.isEmpty()) return
        val firstAddressedObservation = synchronized(this) { addressedNetworks.accept(network) }
        if (firstAddressedObservation) onStage(PhoneWppStage.NETWORK_AVAILABLE)
    }

    companion object {
        private const val TAG = "OAL_WppNetwork"
    }
}
