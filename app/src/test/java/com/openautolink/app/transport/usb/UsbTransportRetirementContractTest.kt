package com.openautolink.app.transport.usb

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTransportRetirementContractTest {

    @Test
    fun `detach is device-scoped and retires the exact delivered pipe`() {
        val manager = source("app/src/main/java/com/openautolink/app/transport/usb/UsbConnectionManager.kt")
        val detach = manager.substringAfter("private fun handleDeviceDetached(device: UsbDevice)")
            .substringBefore("private fun publishCandidates")

        assertTrue(detach.contains("transportOwnership.detach(device.deviceName)"))
        assertTrue(detach.contains("Ignoring detach for"))
        assertTrue(detach.contains("onTransportDetached(detachedPipe, acknowledgeRetired)"))
        assertTrue(detach.contains("retirementPending.compareAndSet(true, false)"))
        assertTrue(manager.contains("currentAasdkPipe = transportPipe"))
        assertTrue(manager.contains("if (retirementPending.get())"))
        assertTrue(manager.contains("private val transportStateLock = Any()"))
        assertTrue(detach.contains("detachedDeviceNames.add(device.deviceName)"))
        val connect = manager.substringAfter("private fun connectToAccessory(device: UsbDevice)")
            .substringBefore("private data class BulkEndpoints")
        assertTrue(connect.contains("synchronized(transportStateLock)"))
        assertTrue(connect.indexOf("detachedDeviceNames.contains(device.deviceName)") <
            connect.indexOf("transportOwnership.claim(device.deviceName)"))
    }

    @Test
    fun `active USB detach closes transport and stops native before replacement`() {
        val session = source("app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt")
        val retire = session.substringAfter("private fun retireDetachedUsbTransport(pipe: AasdkTransportPipe)")
            .substringBefore("private fun handleConnection")

        assertTrue(retire.contains("if (transportPipe !== pipe)"))
        assertTrue(retire.indexOf("explicitStop = true") < retire.indexOf("nativeStopSession()"))
        assertTrue(retire.contains("NativeTransportTeardown.closePipeBeforeNativeStop"))
        assertTrue(retire.contains("Detached USB native attempt retired"))
        assertTrue(session.contains("onTransportDetached = { usbTransportPipe, retired ->"))
        assertTrue(session.contains("if (retireDetachedUsbTransport(usbTransportPipe))"))
        assertTrue(session.contains("replacement remains blocked"))
        assertTrue(session.contains("detachedUsbTransports.add(usbTransportPipe)"))
        assertTrue(retire.contains("detachedUsbTransports.remove(pipe)"))
        val start = session.substringAfter("private fun handleUsbConnection(pipe: AasdkTransportPipe)")
            .substringBefore("private fun retireDetachedUsbTransport")
        assertTrue(start.contains("if (detachedUsbTransports.remove(pipe))"))
        assertTrue(start.indexOf("detachedUsbTransports.remove(pipe)") < start.indexOf("nativeStartSession"))
    }

    private fun source(relativePath: String): String {
        val workingDir = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDir) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate $relativePath")
    }
}
