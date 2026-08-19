package com.openautolink.app.transport.aasdk

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTransportTeardownTest {

    @Test
    fun `blocked transport read exits before native stop begins`() {
        val input = CloseUnblocksInputStream()
        val pipe = AasdkTransportPipe(input, OutputStream.nullOutputStream())
        val readExited = CountDownLatch(1)
        val reader = Thread {
            pipe.readBytes(64)
            readExited.countDown()
        }

        reader.start()
        assertTrue("reader never entered the blocking read", input.readStarted.await(1, TimeUnit.SECONDS))

        var nativeStopObservedReadExit = false
        val remainingPipe = NativeTransportTeardown.closePipeBeforeNativeStop(pipe) {
            nativeStopObservedReadExit = readExited.await(1, TimeUnit.SECONDS)
        }

        reader.join(1_000)
        assertTrue("closing the pipe must unblock the native reader before join", nativeStopObservedReadExit)
        assertTrue("reader thread remained blocked after pipe close", !reader.isAlive)
        assertNull("the closed pipe must not remain reusable", remainingPipe)
    }

    @Test
    fun `socket read timeout is a nonterminal empty poll`() {
        val timeoutInput = object : InputStream() {
            override fun read(): Int = throw SocketTimeoutException("poll timeout")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw SocketTimeoutException("poll timeout")
        }
        val pipe = AasdkTransportPipe(timeoutInput, OutputStream.nullOutputStream())

        val result = pipe.readBytes(64)

        assertTrue("a read timeout must keep the native reader alive", result != null && result.isEmpty())
    }

    @Test
    fun `zero-byte transport poll is nonterminal`() {
        val zeroInput = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        val pipe = AasdkTransportPipe(zeroInput, OutputStream.nullOutputStream())

        val result = pipe.readBytes(64)

        assertTrue("a bounded zero-byte poll must keep the native reader alive", result != null && result.isEmpty())
    }

    @Test
    fun `USB bulk timeout returns control to native stop flag`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/transport/usb/UsbTransportPipe.kt",
        ).readText()
        val read = source.substringAfter("override fun read(b: ByteArray, off: Int, len: Int): Int")
            .substringBefore("override fun close()")

        assertTrue("USB timeout must return an empty poll instead of looping internally", read.contains("if (result <= 0) return 0"))
    }

    @Test
    fun `TCP socket read is bounded before streams reach native transport`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt",
        ).readText()
        val handleConnection = source.substringAfter("private fun handleConnection(socket: Socket)")
            .substringBefore("fun shutdownGracefully")
        val timeout = handleConnection.indexOf("socket.soTimeout = AasdkTransportPipe.READ_POLL_TIMEOUT_MS")
        val input = handleConnection.indexOf("socket.getInputStream()")

        assertTrue("TCP timeout must be installed before native reader gets the stream", timeout >= 0 && input >= 0 && timeout < input)
    }

    @Test
    fun `all session teardown paths close the pipe before native stop`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt",
        ).readText()
        val explicitStop = source.substringAfter("fun stop()")
            .substringBefore("fun forceReconnect(reason: String)")
        val forceReconnect = source.substringAfter("fun forceReconnect(reason: String)")
            .substringBefore("// -- Input forwarding")
        val orderedCall = "transportPipe = NativeTransportTeardown.closePipeBeforeNativeStop(transportPipe)"

        assertTrue("explicit stop must use ordered transport teardown", explicitStop.contains(orderedCall))
        assertTrue("forced reconnect must use ordered transport teardown", forceReconnect.contains(orderedCall))
    }

    @Test
    fun `native transport closes Java pipe before joining read thread`() {
        val header = projectFile("app/src/main/cpp/jni_transport.h").readText()
        val source = projectFile("app/src/main/cpp/jni_transport.cpp").readText()
        val constructor = source.substringAfter("JniTransport::JniTransport(")
            .substringBefore("JniTransport::~JniTransport()")
        val stop = source.substringAfter("void JniTransport::stop()")
            .substringBefore("void JniTransport::onDataReceived")
        val closeCall = stop.indexOf("CallVoidMethod(javaTransport_, closeMethodId_)")
        val joinCall = stop.indexOf("readThread_.join()")

        assertTrue("native transport must cache the Java close method", header.contains("jmethodID closeMethodId_"))
        assertTrue("constructor must resolve AasdkTransportPipe.close", constructor.contains("GetMethodID(cls, \"close\", \"()V\")"))
        assertTrue("native stop must call Java close", closeCall >= 0)
        assertTrue("native stop must close the pipe before joining its reader", joinCall >= 0 && closeCall < joinCall)
    }

    private fun projectFile(path: String): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir unavailable"))
        repeat(8) {
            val candidate = File(dir, path)
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: error("Project root not found for: $path")
        }
        error("Project file not found: $path")
    }

    private class CloseUnblocksInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        private val closeCalled = CountDownLatch(1)
        private val closed = AtomicBoolean(false)

        override fun read(): Int {
            readStarted.countDown()
            closeCalled.await()
            return if (closed.get()) -1 else 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readStarted.countDown()
            closeCalled.await()
            return if (closed.get()) -1 else 0
        }

        override fun close() {
            closed.set(true)
            closeCalled.countDown()
        }
    }
}
