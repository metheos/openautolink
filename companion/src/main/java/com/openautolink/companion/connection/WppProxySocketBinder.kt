package com.openautolink.companion.connection

import com.openautolink.companion.network.ProcessNetworkBindingLock
import com.openautolink.companion.service.TcpAdvertiser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Binds the phone-local endpoint Android Auto receives through WPP. */
object WppProxySocketBinder {
    fun bind(): ServerSocket = ProcessNetworkBindingLock.withLock {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(
            InetSocketAddress(
                InetAddress.getByName("127.0.0.1"),
                TcpAdvertiser.WPP_PROXY_PORT,
            ),
        )
        server
    }
}
