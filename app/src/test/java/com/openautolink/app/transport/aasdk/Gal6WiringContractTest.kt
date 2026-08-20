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
    fun `GAL version ladder is writable visible and carried into every session path`() {
        val preferences = projectFile("app/src/main/java/com/openautolink/app/data/AppPreferences.kt")
        val settingsVm = projectFile("app/src/main/java/com/openautolink/app/ui/settings/SettingsViewModel.kt")
        val settingsUi = projectFile("app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt")
        val projectionVm = projectFile("app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt")
        val settingsReceiver = projectFile("app/src/main/java/com/openautolink/app/diagnostics/SettingsReceiver.kt")
        val sessionManager = projectFile("app/src/main/java/com/openautolink/app/session/SessionManager.kt")

        assertTrue(preferences.contains("val galVersion: Flow<String>"))
        assertTrue(preferences.contains("suspend fun setGalVersion"))
        assertTrue(preferences.contains("stringPreferencesKey(\"gal_version_v2\")"))
        assertTrue(preferences.contains("remove(EXPERIMENTAL_GAL6)"))
        assertTrue(settingsVm.contains("fun updateGalVersion"))
        assertTrue(settingsVm.contains("val galVersion: StateFlow<String>"))
        assertTrue(settingsUi.contains("testTag(\"galVersion_"))
        assertTrue(settingsUi.contains("GalProtocolPolicy.supportedVersions.forEach"))
        assertTrue(projectionVm.contains("preferences.galVersion.first()"))
        assertTrue(settingsReceiver.contains("galVersion = prefs.galVersion.first()"))
        assertTrue(sessionManager.contains("galVersion: String = AppPreferences.DEFAULT_GAL_VERSION"))
        assertTrue(sessionManager.contains("galVersion = galVersion"))
    }

    @Test
    fun `native session applies cumulative GAL version media and session policy`() {
        val native = projectFile("app/src/main/cpp/jni_session.cpp")
        val nativeHeader = projectFile("app/src/main/cpp/jni_session.h")
        val handlers = projectFile("app/src/main/cpp/jni_channel_handlers.cpp")
        val handlerHeader = projectFile("app/src/main/cpp/jni_channel_handlers.h")

        assertTrue(native.contains("modernDisplayPolicy"))
        assertTrue(native.contains("singleVideoCodecFamily"))
        assertTrue(native.contains("useActiveMediaSessionIds"))
        assertTrue(native.contains("requestedGalMajor_"))
        assertTrue(native.contains("requestedGalMinor_"))
        assertTrue(native.contains("GAL policy: requested="))
        assertTrue(native.contains("activeVideoSessionId_"))
        assertTrue(native.contains("videoAckSessionId(activeVideoSessionId_.load())"))
        assertTrue(native.contains("mediaSetupMaxUnacked()"))
        assertTrue(native.contains("expectedHevcGopFrames"))
        assertTrue(nativeHeader.contains("shouldSendAudioAcks"))
        assertTrue(nativeHeader.contains("audioAckSessionId"))
        assertTrue(nativeHeader.contains("videoAckSessionId"))
        assertTrue(handlers.contains("activeSessionId_"))
        assertTrue(handlers.contains("session_.shouldSendAudioAcks()"))
        assertTrue(handlers.contains("session_.audioAckSessionId(activeSessionId_.load())"))
        assertTrue(handlerHeader.contains("activeSessionId_"))
    }

    @Test
    fun `lower modern GAL version response aborts before TLS handshake`() {
        val native = projectFile("app/src/main/cpp/jni_session.cpp")
        val response = native.substringAfter("void JniSession::onVersionResponse")
            .substringBefore("void JniSession::onAuthComplete")

        val admission = response.indexOf("gal::versionResponseIsAccepted")
        val abort = response.indexOf("GAL version negotiation rejected", startIndex = admission)
        val handshake = response.indexOf("cryptor_->doHandshake()")
        assertTrue(admission >= 0)
        assertTrue(abort > admission)
        assertTrue(handshake > abort)
    }

    @Test
    fun `malformed GAL response aborts immediately with a named diagnostic`() {
        val native = projectFile("app/src/main/cpp/jni_session.cpp")
        val malformed = native.substringAfter("void JniSession::onVersionResponseMalformed")
            .substringBefore("void JniSession::onVersionResponse")

        assertTrue(malformed.contains("GAL version response malformed"))
        assertTrue(malformed.contains("triggerAbort(reason)"))
    }

    @Test
    fun `enriched envelopes remain typed and reconstructable without rejecting unknown fields`() {
        val native = projectFile("app/src/main/cpp/jni_session.cpp")
        val handlers = projectFile("app/src/main/cpp/jni_channel_handlers.cpp")

        assertTrue(native.contains("reportGalStartEnvelope(\"video\", indication)"))
        assertTrue(handlers.contains("session_.reportGalStartEnvelope(channelName, indication)"))
        assertTrue(native.contains("MediaOptions typed"))
        assertTrue(handlers.contains("reportVehicleEnergyForecast"))
        assertTrue(native.contains("unknown_fields="))
        assertTrue(native.contains("total_chunks="))
    }
}
