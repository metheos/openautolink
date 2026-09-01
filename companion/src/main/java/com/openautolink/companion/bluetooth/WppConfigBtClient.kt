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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.util.UUID

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
        val bonded = adapter.bondedDevices
            ?.filter { it.address.trim().lowercase() in normalizedTargets }
            .orEmpty()
        if (bonded.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No selected car Bluetooth devices are paired"))
        }

        var sent = 0
        bonded.forEach { device ->
            if (sendToDevice(device, ssid, bssid)) {
                sent += 1
            }
        }
        if (sent == 0) Result.failure(IllegalStateException("No selected car device acknowledged the WPP config update"))
        else Result.success(sent)
    }

    @SuppressLint("MissingPermission")
    private fun sendToDevice(device: BluetoothDevice, ssid: String, bssid: String): Boolean {
        val socket = try {
            device.createRfcommSocketToServiceRecord(CONFIG_UUID)
        } catch (e: Exception) {
            CompanionLog.w(TAG, "Socket create failed for ${device.address}: ${e.message}")
            return false
        }
        return try {
            socket.connect()

            val payload = JSONObject()
                .put("ssid", ssid)
                .put("bssid", bssid)
                .toString() + "\n"

            OutputStreamWriter(socket.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }

            val ackLine = runBlocking {
                withTimeout(5000L) {
                    socket.inputStream.bufferedReader(Charsets.UTF_8).use { it.readLine() }
                }
            }
            if (ackLine == null || ackLine.trim() !in setOf("OK", "ACK")) {
                CompanionLog.w(TAG, "No ACK from ${device.address} for WPP config update")
                false
            } else {
                CompanionLog.i(TAG, "Confirmed WPP update from ${device.address}: $ackLine")
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
