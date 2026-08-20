package com.openautolink.app.transport.aasdk

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Gal6WiringContractTest {

    private fun projectFile(relative: String): String {
        val start = File(requireNotNull(System.getProperty("user.dir")))
        val root = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not locate project root from $start")
        return File(root, relative).readText()
    }

    @Test
    fun `experimental toggle is writable visible and carried into every session path`() {
        val preferences = projectFile("app/src/main/java/com/openautolink/app/data/AppPreferences.kt")
        val settingsVm = projectFile("app/src/main/java/com/openautolink/app/ui/settings/SettingsViewModel.kt")
        val settingsUi = projectFile("app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt")
        val projectionVm = projectFile("app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt")
        val sessionManager = projectFile("app/src/main/java/com/openautolink/app/session/SessionManager.kt")

        assertTrue(preferences.contains("val experimentalGal6: Flow<Boolean>"))
        assertTrue(preferences.contains("suspend fun setExperimentalGal6"))
        assertTrue(settingsVm.contains("fun updateExperimentalGal6"))
        assertTrue(settingsVm.contains("val experimentalGal6: StateFlow<Boolean>"))
        assertTrue(settingsVm.contains("experimentalGal6 = arr[8] as Boolean"))
        assertTrue(settingsUi.contains("testTag(\"experimentalGal6Toggle\")"))
        assertTrue(settingsUi.contains("Experimental GAL 6.0"))
        assertTrue(projectionVm.contains("preferences.experimentalGal6.first()"))
        assertTrue(projectionVm.contains("experimentalGal6 = experimentalGal6"))
        assertTrue(sessionManager.contains("experimentalGal6: Boolean = false"))
        assertTrue(sessionManager.contains("experimentalGal6 = experimentalGal6"))
    }

    @Test
    fun `native session applies GAL6 version media window ack and session policy`() {
        val native = projectFile("app/src/main/cpp/jni_session.cpp")
        val nativeHeader = projectFile("app/src/main/cpp/jni_session.h")
        val handlers = projectFile("app/src/main/cpp/jni_channel_handlers.cpp")
        val handlerHeader = projectFile("app/src/main/cpp/jni_channel_handlers.h")

        assertTrue(native.contains("experimentalGal6"))
        assertTrue(native.contains("requestedGalMajor_"))
        assertTrue(native.contains("requestedGalMinor_"))
        assertTrue(native.contains("GAL policy: requested="))
        assertTrue(native.contains("activeVideoSessionId_"))
        assertTrue(native.contains("ack.set_session_id(mediaAckSessionId(activeVideoSessionId_.load()))"))
        assertTrue(native.contains("mediaSetupMaxUnacked()"))
        assertTrue(native.contains("expectedHevcGopFrames"))
        assertTrue(nativeHeader.contains("shouldSendAudioAcks"))
        assertTrue(nativeHeader.contains("return sdrConfig_.experimentalGal6 ? activeSessionId : 0"))
        assertTrue(handlers.contains("activeSessionId_"))
        assertTrue(handlers.contains("session_.shouldSendAudioAcks()"))
        assertTrue(handlers.contains("session_.mediaAckSessionId(activeSessionId_.load())"))
        assertTrue(handlerHeader.contains("activeSessionId_"))
    }

    @Test
    fun `rejected GAL version aborts before TLS handshake`() {
        val native = projectFile("app/src/main/cpp/jni_session.cpp")
        val response = native.substringAfter("void JniSession::onVersionResponse")
            .substringBefore("void JniSession::onAuthComplete")

        val rejection = response.indexOf("status != aap_protobuf::shared::STATUS_SUCCESS")
        val abort = response.indexOf("GAL version negotiation rejected")
        val handshake = response.indexOf("cryptor_->doHandshake()")
        assertTrue(rejection >= 0)
        assertTrue(abort > rejection)
        assertTrue(handshake > abort)
    }

    @Test
    fun `GAL6 enriched envelopes remain observable without rejecting unknown fields`() {
        val native = projectFile("app/src/main/cpp/jni_session.cpp")
        val handlers = projectFile("app/src/main/cpp/jni_channel_handlers.cpp")

        assertTrue(native.contains("reportGal6StartEnvelope(\"video\", indication)"))
        assertTrue(handlers.contains("session_.reportGal6StartEnvelope(channelName, indication)"))
        assertTrue(native.contains("unknown_fields="))
    }
}
