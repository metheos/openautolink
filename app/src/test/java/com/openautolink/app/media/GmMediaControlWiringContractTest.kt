package com.openautolink.app.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GmMediaControlWiringContractTest {

    @Test
    fun `experimental control mode is off by default and reachable from Settings`() {
        val preferences = source("app/src/main/java/com/openautolink/app/data/AppPreferences.kt")
        val settingsVm = source("app/src/main/java/com/openautolink/app/ui/settings/SettingsViewModel.kt")
        val settingsUi = source("app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt")

        assertTrue(preferences.contains("EXPERIMENTAL_GM_MEDIA_CONTROLS"))
        assertTrue(preferences.contains("DEFAULT_EXPERIMENTAL_GM_MEDIA_CONTROLS = false"))
        assertTrue(preferences.contains("val experimentalGmMediaControls: Flow<Boolean>"))
        assertTrue(preferences.contains("suspend fun setExperimentalGmMediaControls"))
        assertTrue(settingsVm.contains("experimentalGmMediaControls"))
        assertTrue(settingsVm.contains("updateExperimentalGmMediaControls"))
        assertTrue(settingsUi.contains("Experimental GM media controls"))
        assertTrue(settingsUi.contains("experimentalGmMediaControlsToggle"))
    }

    @Test
    fun `enabled MediaSession controls and both GM mute channels reach the current AA session`() {
        val media = source("app/src/main/java/com/openautolink/app/media/OalMediaSessionManager.kt")
        val manager = source("app/src/main/java/com/openautolink/app/session/SessionManager.kt")
        val aasdk = source("app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt")
        val nativeApi = source("app/src/main/java/com/openautolink/app/transport/aasdk/AasdkNative.kt")
        val jni = source("app/src/main/cpp/aasdk_jni.cpp")
        val nativeImpl = source("app/src/main/cpp/jni_session.cpp")

        assertTrue(media.contains("override fun onStop()"))
        assertTrue(media.contains("GmMediaControlPolicy.Command.STOP"))
        assertTrue(media.contains("PlaybackStateCompat.ACTION_STOP"))
        assertTrue(media.contains("if (experimentalControlsEnabled) actions = actions or PlaybackStateCompat.ACTION_STOP"))
        assertTrue(media.contains("buildPlaybackState(lastPushedState, lastPushedPosition)"))
        assertTrue(manager.contains("setExperimentalControlsEnabled(false)"))
        assertTrue(manager.contains("setExperimentalControlsEnabled(true)"))
        assertTrue(manager.contains("preferences.experimentalGmMediaControls.distinctUntilChanged()"))
        assertTrue(manager.contains("MASTER_MUTE_CHANGED_ACTION"))
        assertTrue(manager.contains("STREAM_MUTE_CHANGED_ACTION"))
        assertTrue(manager.contains("EXTRA_MASTER_VOLUME_MUTED"))
        assertTrue(manager.contains("manager.isStreamMute(AudioManager.STREAM_MUSIC)"))
        assertTrue(manager.contains("routeMediaSessionCommand"))
        assertTrue(manager.contains("syncExperimentalHuMuteState"))
        assertTrue(manager.contains("primeExperimentalUnmutedState"))
        val receiverBlock = manager
            .substringAfter("private fun registerMuteStateReceiver()")
            .substringBefore("private fun unregisterMuteStateReceiver()")
        assertTrue(receiverBlock.contains("scope.launch { syncExperimentalHuMuteState"))
        assertTrue(aasdk.contains("fun sendKeyPress"))
        assertTrue(aasdk.contains("currentNativeSessionGeneration"))
        assertTrue(aasdk.contains("fun setHeadUnitMuted"))
        val nativeGateSetter = aasdk
            .substringAfter("fun setExperimentalMediaControlsEnabled")
            .substringBefore("fun sendGpsLocation")
        assertTrue(nativeGateSetter.contains("synchronized(connectionStartLock)"))
        assertTrue(nativeApi.contains("nativeSendKeyPress"))
        assertTrue(nativeApi.contains("nativeSetHeadUnitMuted"))
        assertTrue(nativeApi.contains("nativePrimeHeadUnitMuted"))
        assertTrue(nativeApi.contains("nativeSetExperimentalMediaControlsEnabled"))
        assertTrue(jni.contains("AasdkNative_nativeSendKeyPress"))
        assertTrue(jni.contains("AasdkNative_nativeSetHeadUnitMuted"))
        assertTrue(jni.contains("gSessionGeneration.load() != expectedGeneration"))
        val keyPressBody = nativeImpl
            .substringAfter("bool JniSession::sendKeyPress")
            .substringBefore("void JniSession::sendGpsLocation")
        assertTrue(keyPressBody.contains("for (bool down : {true, false})"))
        assertTrue(keyPressBody.contains("edges == 2"))
        assertTrue(keyPressBody.contains("experimentalMediaControlsEnabled_"))
        val muteSetterBody = nativeImpl
            .substringAfter("bool JniSession::setHeadUnitMuted")
            .substringBefore("bool JniSession::flushHeadUnitAudioFocusState")
        assertTrue(muteSetterBody.contains("ioService_->post"))
        assertFalse(muteSetterBody.contains("controlChannel_"))
        assertFalse(muteSetterBody.contains("strand_"))
        val setupFocusBody = nativeImpl
            .substringAfter("void JniSession::sendUnsolicitedAudioFocusGain")
            .substringBefore("bool JniSession::setHeadUnitMuted")
        assertTrue(setupFocusBody.contains("if (!experimentalMediaControlsEnabled_.load())"))
        assertTrue(setupFocusBody.contains("AUDIO_FOCUS_STATE_GAIN"))
        assertTrue(nativeImpl.contains("Key press send complete:"))
        assertTrue(nativeImpl.contains("AA audio focus sync outcome=sent:"))
    }

    @Test
    fun `existing custom key remapping remains independent of experimental MediaSession controls`() {
        val steering = source("app/src/main/java/com/openautolink/app/input/SteeringWheelController.kt")
        val customBranch = steering
            .substringAfter("val customMapped = customKeyMap[keycode]")
            .substringBefore("return when")

        assertTrue(customBranch.contains("sendButtonToAA(customMapped"))
        assertFalse(steering.contains("experimentalGmMediaControls"))
        assertFalse(steering.contains("GmMediaControlPolicy"))
    }

    private fun source(path: String): String = projectFile(path).readText()

    private fun projectFile(path: String): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir unavailable"))
        repeat(8) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("Cannot locate project file: $path")
    }
}
