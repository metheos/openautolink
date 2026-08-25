package com.openautolink.app.audio

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLifecycleWiringContractTest {

    @Test
    fun `native media start and stop reach Kotlin control messages`() {
        val callback = projectFile(
            "app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSessionCallback.kt",
        ).readText()
        val kotlinSession = projectFile(
            "app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt",
        ).readText()
        val nativeHeader = projectFile("app/src/main/cpp/jni_session.h").readText()
        val nativeSession = projectFile("app/src/main/cpp/jni_session.cpp").readText()
        val handlers = projectFile("app/src/main/cpp/jni_channel_handlers.cpp").readText()
        val manager = projectFile(
            "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
        ).readText()

        assertTrue(callback.contains("fun onAudioStart(purpose: Int, sampleRate: Int, channels: Int)"))
        assertTrue(callback.contains("fun onAudioStop(purpose: Int)"))

        assertTrue(nativeHeader.contains("jmethodID onAudioStart"))
        assertTrue(nativeHeader.contains("jmethodID onAudioStop"))
        assertTrue(nativeHeader.contains("void dispatchAudioStart("))
        assertTrue(nativeHeader.contains("void dispatchAudioStop("))
        assertTrue(nativeSession.contains("GetMethodID(cbClass, \"onAudioStart\", \"(III)V\")"))
        assertTrue(nativeSession.contains("GetMethodID(cbClass, \"onAudioStop\", \"(I)V\")"))
        assertTrue(nativeSession.contains("CallVoidMethod(callbackRef_, cbMethods_.onAudioStart"))
        assertTrue(nativeSession.contains("CallVoidMethod(callbackRef_, cbMethods_.onAudioStop"))

        val startHandler = handlers.substringAfter("onMediaChannelStartIndication(")
            .substringBefore("onMediaChannelStopIndication(")
        val stopHandler = handlers.substringAfter("onMediaChannelStopIndication(")
            .substringBefore("onMediaWithTimestampIndication(")
        assertTrue(startHandler.contains("session_.dispatchAudioStart("))
        assertTrue(stopHandler.contains("session_.dispatchAudioStop(purposeFromType())"))

        assertTrue(kotlinSession.contains("override fun onAudioStart("))
        assertTrue(kotlinSession.contains("ControlMessage.AudioStart("))
        assertTrue(kotlinSession.contains("override fun onAudioStop(purpose: Int)"))
        assertTrue(kotlinSession.contains("ControlMessage.AudioStop("))
        val audioCallbacks = kotlinSession.substringAfter("override fun onAudioStart(")
            .substringBefore("override fun onMicRequest(")
        assertTrue(kotlinSession.contains("Channel<Unit>(Channel.CONFLATED)"))
        assertTrue(kotlinSession.contains("pendingAudioLifecycle = AudioLifecycleMailbox()"))
        assertTrue(kotlinSession.contains("pendingAudioLifecycle.poll()"))
        assertTrue(kotlinSession.contains("pendingAudioLifecycle.offer(message)"))
        assertTrue(kotlinSession.contains("pendingAudioLifecycle.remove(message)"))
        assertTrue(kotlinSession.contains("audioLifecycleClosed = AtomicBoolean(false)"))
        assertTrue(kotlinSession.contains("audioLifecycleJob.cancel()"))
        assertTrue(kotlinSession.contains("pendingAudioLifecycle.clear()"))
        assertTrue(kotlinSession.contains("if (audioLifecycleClosed.get())"))
        assertTrue(!kotlinSession.contains("Channel.UNLIMITED"))
        assertTrue(kotlinSession.contains("for (signal in audioLifecycleSignals)"))
        val lifecycleConsumer = kotlinSession.substringAfter("private val audioLifecycleJob: Job")
            .substringBefore("private val _vehicleEnergyForecast")
        assertTrue(lifecycleConsumer.contains("synchronized(audioLifecycleLock)"))
        assertTrue(lifecycleConsumer.contains("_controlMessages.tryEmit(message)"))
        assertTrue(lifecycleConsumer.contains("pendingAudioLifecycle.offerFirst(message)"))
        assertTrue(lifecycleConsumer.contains("delay(1)"))
        assertTrue(!lifecycleConsumer.contains("_controlMessages.emit(message)"))
        assertTrue(
            "Publication and final close must share one non-suspending lock boundary",
            lifecycleConsumer.indexOf("synchronized(audioLifecycleLock)") <
                lifecycleConsumer.indexOf("_controlMessages.tryEmit(message)"),
        )
        val finalStop = kotlinSession.substringAfter("fun stop()")
            .substringBefore("fun forceReconnect")
        assertTrue(finalStop.contains("closeAudioLifecycle()"))
        assertTrue(
            "Final teardown must retire lifecycle delivery before stopping transports",
            finalStop.indexOf("closeAudioLifecycle()") < finalStop.indexOf("_tcpConnector?.stop()"),
        )
        assertTrue(audioCallbacks.contains("queueAudioLifecycle("))
        assertTrue(
            "Native audio lifecycle callbacks share one strand; separate coroutine launches can reorder them",
            !audioCallbacks.contains("scope.launch"),
        )
        assertTrue(audioCallbacks.contains("Audio lifecycle control rejected:"))

        assertTrue(manager.contains("is ControlMessage.AudioStart ->"))
        assertTrue(manager.contains("_audioPlayer?.startPurpose("))
        assertTrue(manager.contains("is ControlMessage.AudioStop ->"))
        assertTrue(manager.contains("_audioPlayer?.stopPurpose(message.purpose)"))
    }

    private fun projectFile(path: String): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir unavailable"))
        repeat(8) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate $path")
    }
}
