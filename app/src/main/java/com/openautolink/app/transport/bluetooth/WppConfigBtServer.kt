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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

data class AppliedWppConfig(
    val version: Long,
    val ssid: String,
    val bssid: String,
)

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
    private val _status = MutableStateFlow("Not started")
    val status: StateFlow<String> = _status
    private val _appliedConfig = MutableStateFlow<AppliedWppConfig?>(null)
    val appliedConfig: StateFlow<AppliedWppConfig?> = _appliedConfig

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
    private fun acceptLoop() {
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
                OalLog.i(TAG, "Accepted WPP BT socket from $remote")
                val inStream = DataInputStream(socket.inputStream)
                val payloadLength = inStream.readInt()
                OalLog.i(TAG, "WPP BT payload length from $remote = $payloadLength")
                if (payloadLength <= 0 || payloadLength > 4096) {
                    OalLog.w(TAG, "Rejected WPP config payload length from $remote: $payloadLength")
                    DataOutputStream(socket.outputStream).use { it.writeUTF("ERR") }
                    return@launch
                }

                val payloadBytes = ByteArray(payloadLength)
                var read = 0
                while (read < payloadLength) {
                    val chunk = inStream.read(payloadBytes, read, payloadLength - read)
                    if (chunk < 0) {
                        OalLog.w(TAG, "BT socket closed before full WPP payload from $remote")
                        return@launch
                    }
                    read += chunk
                }
                OalLog.i(TAG, "Received WPP BT payload bytes from $remote: ${payloadBytes.size}")

                val line = payloadBytes.toString(Charsets.UTF_8).trim()
                OalLog.i(TAG, "WPP BT payload text from $remote: $line")
                if (line.isEmpty()) {
                    OalLog.w(TAG, "Empty WPP config payload from $remote")
                    return@launch
                }

                val json = JSONObject(line)
                val ssid = json.optString("ssid", "").trim()
                val bssid = json.optString("bssid", "").trim()
                if (ssid.isBlank() || bssid.isBlank()) {
                    updateStatus("Rejected invalid WPP config payload from $remote")
                    OalLog.w(TAG, "Invalid WPP config payload from $remote")
                    DataOutputStream(socket.outputStream).use { it.writeUTF("ERR") }
                    return@launch
                }

                val prefs = AppPreferences.getInstance(context)
                prefs.setHotspotSsid(ssid)
                prefs.setWppBssid(bssid)
                _appliedConfig.value = AppliedWppConfig(
                    version = (_appliedConfig.value?.version ?: 0L) + 1L,
                    ssid = ssid,
                    bssid = bssid,
                )
                updateStatus("Applied WPP SSID/BSSID from $remote")
                OalLog.i(TAG, "Received WPP Wi‑Fi config from $remote ssid=$ssid bssid=$bssid")
                DataOutputStream(socket.outputStream).use { it.writeUTF("OK") }
                stop()
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
    }

    private fun updateStatus(value: String) {
        _status.value = value
    }
}
