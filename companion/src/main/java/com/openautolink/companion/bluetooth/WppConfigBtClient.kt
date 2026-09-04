package com.openautolink.companion.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.openautolink.companion.diagnostics.CompanionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

object WppConfigBtClient {
    private const val TAG = "WppConfigBt"
    private val CONFIG_UUID: UUID = UUID.fromString("8a0d7f20-8f8d-4b1f-9f0d-2f8a4fd8d8a1")

    @SuppressLint("MissingPermission")
    suspend fun sendToTargetCars(
        context: Context,
        targetMacs: Set<String>,
        ssid: String,
        bssid: String,
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Result.failure(IllegalStateException("BLUETOOTH_CONNECT not granted"))
        }
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return@withContext Result.failure(IllegalStateException("Bluetooth unavailable"))
        if (!adapter.isEnabled) {
            return@withContext Result.failure(IllegalStateException("Bluetooth disabled"))
        }

        val normalizedTargets = targetMacs.map { it.trim().lowercase() }.toSet()
        val candidates = adapter.bondedDevices
            ?.filter { it.address.trim().lowercase() in normalizedTargets }
            ?.sortedBy { it.address }
            .orEmpty()

        val active = candidates.filter { isConnectedDevice(it) }
        val targets = if (active.isNotEmpty()) active else candidates
        if (targets.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No selected car Bluetooth devices are paired"))
        }
        if (active.isEmpty()) {
            CompanionLog.w(TAG, "No active ACL link detected for target car device(s); using paired device list anyway")
        }

        var sent = 0
        targets.forEach { device ->
            if (sendToDevice(device, ssid, bssid)) {
                sent += 1
            }
        }
        if (sent == 0) Result.failure(IllegalStateException("No selected car device acknowledged the WPP config update"))
        else Result.success(sent)
    }

    @SuppressLint("MissingPermission")
    private fun isConnectedDevice(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("isConnected")
            (method.invoke(device) as? Boolean) == true
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendToDevice(
        device: BluetoothDevice,
        ssid: String,
        bssid: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val socket = try {
            device.createRfcommSocketToServiceRecord(CONFIG_UUID)
        } catch (e: Exception) {
            CompanionLog.w(TAG, "Socket create failed for ${device.address}: ${e.message}")
            return@withContext false
        }
        try {
            CompanionLog.i(TAG, "Opening WPP BT socket to ${device.address}")
            socket.connect()
            CompanionLog.i(TAG, "Connected WPP BT socket to ${device.address}")

            val payload = JSONObject()
                .put("ssid", ssid)
                .put("bssid", bssid)
                .toString()
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)
            CompanionLog.i(TAG, "Sending WPP payload to ${device.address}: ${payloadBytes.size} bytes")

            val out = DataOutputStream(socket.outputStream)
            out.writeInt(payloadBytes.size)
            out.write(payloadBytes)
            out.flush()

            val ack = withTimeout(5.seconds) {
                val raw = DataInputStream(socket.inputStream).readUTF()
                CompanionLog.i(TAG, "Received WPP ACK from ${device.address}: $raw")
                raw
            }
            if (ack.trim() !in setOf("OK", "ACK", "ERR")) {
                CompanionLog.w(TAG, "No ACK from ${device.address} for WPP config update")
                false
            } else if (ack.trim() == "ERR") {
                CompanionLog.w(TAG, "Car rejected WPP config update for ${device.address}")
                false
            } else {
                CompanionLog.i(TAG, "Confirmed WPP update from ${device.address}: $ack")
                true
            }
        } catch (e: Exception) {
            CompanionLog.w(TAG, "Send failed for ${device.address}: ${e.message}")
            false
        } finally {
            runCatching { socket.close() }
        }
    }
}
