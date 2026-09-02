package com.openautolink.app.transport.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

/**
 * Process-scope RFCOMM side channel for receiving WPP Wi-Fi config from the companion.
 *
 * This is intentionally separate from the Android Auto WPP RFCOMM handshake UUID. The
 * AA handshake stream is owned by gearhead's protocol; custom payloads there would be risky.
 *
 * Payload format: one JSON line terminated by '\n'
 *   {"ssid":"CarWifi","bssid":"AA:BB:CC:DD:EE:FF"}
 */
class WppConfigBtServer(
    private val context: Context,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob() + Dispatchers.IO)
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            updateStatus("Start failed: BLUETOOTH_CONNECT not granted")
            OalLog.w(TAG, "BLUETOOTH_CONNECT not granted — WPP config listener not started")
            return
        }
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            updateStatus("Start failed: Bluetooth disabled")
            OalLog.w(TAG, "Bluetooth unavailable or disabled — WPP config listener not started")
            return
        }
        running = true
        updateStatus("Starting RFCOMM listener…")
        acceptJob = scope.launch { acceptLoop() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun acceptLoop() {
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            updateStatus("Start failed: no Bluetooth adapter")
            running = false
            return
        }
        if (!adapter.isEnabled) {
            updateStatus("Start failed: Bluetooth disabled")
            running = false
            return
        }
        try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, CONFIG_UUID)
            updateStatus("Listening for companion SSID/BSSID config")
            OalLog.i(TAG, "WPP config RFCOMM listener up (uuid=$CONFIG_UUID)")
        } catch (e: Exception) {
            updateStatus("Start failed: ${e.message}")
            OalLog.w(TAG, "listenUsingRfcommWithServiceRecord failed: ${e.message}")
            running = false
            return
        }

        while (running && scope.isActive) {
            val client = try {
                serverSocket?.accept()
            } catch (e: Exception) {
                if (running) OalLog.w(TAG, "accept() failed: ${e.message}")
                null
            }
            if (client == null) continue
            handleClient(client)
        }
    }

    private fun handleClient(socket: BluetoothSocket) {
        scope.launch {
            val remote = runCatching { socket.remoteDevice?.address ?: "?" }.getOrDefault("?")
            try {
                BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8)).use { reader ->
                    val line = reader.readLine()?.trim()
                    if (line.isNullOrEmpty()) {
                        OalLog.w(TAG, "Empty WPP config payload from $remote")
                        return@use
                    }
                    val json = JSONObject(line)
                    val ssid = json.optString("ssid", "").trim()
                    val bssid = json.optString("bssid", "").trim()
                    if (ssid.isBlank() || bssid.isBlank()) {
                        updateStatus("Rejected invalid WPP config payload from $remote")
                        OalLog.w(TAG, "Invalid WPP config payload from $remote")
                        socket.outputStream.writer(Charsets.UTF_8).use { it.write("ERR\n"); it.flush() }
                        return@use
                    }
                    scope.launch {
                        val prefs = AppPreferences.getInstance(context)
                        prefs.setHotspotSsid(ssid)
                        prefs.setWppBssid(bssid)
                    }
                    updateStatus("Applied WPP SSID/BSSID from $remote")
                    OalLog.i(TAG, "Received WPP Wi‑Fi config from $remote ssid=$ssid bssid=$bssid")
                    socket.outputStream.writer(Charsets.UTF_8).use { writer ->
                        writer.write("OK\n")
                        writer.flush()
                    }
                    stop()
                }
            } catch (e: Exception) {
                OalLog.w(TAG, "Failed reading WPP config from $remote: ${e.message}")
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    fun stop() {
        running = false
        updateStatus("Stopped")
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        OalLog.i(TAG, "WPP config RFCOMM listener stopped")
    }

    companion object {
        private const val TAG = "WppConfigBt"
        private const val SDP_NAME = "OpenAutoLink WPP Config"
        val CONFIG_UUID: UUID = UUID.fromString("8a0d7f20-8f8d-4b1f-9f0d-2f8a4fd8d8a1")

        @Volatile
        private var instance: WppConfigBtServer? = null
        private val _status = MutableStateFlow("Not started")
        val status: StateFlow<String> = _status

        fun getOrCreateInstance(context: Context, parentScope: CoroutineScope): WppConfigBtServer {
            return instance ?: synchronized(this) {
                instance ?: WppConfigBtServer(context.applicationContext, parentScope).also { instance = it }
            }
        }

        fun currentStatus(): String = _status.value

        private fun updateStatus(value: String) {
            _status.value = value
        }
    }
}
