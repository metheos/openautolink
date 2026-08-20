package com.openautolink.app.transport.aasdk

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Gal6ModernMessageContractTest {
    private fun projectFile(relative: String): String {
        val start = File(requireNotNull(System.getProperty("user.dir")))
        val root = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not locate project root from $start")
        return File(root, relative).readText()
    }

    @Test
    fun `aasdk explicitly accepts GAL6 media options on video and audio`() {
        val ids = projectFile("external/opencardev-aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto")
        val videoApi = projectFile("external/opencardev-aasdk/include/aasdk/Channel/MediaSink/Video/IVideoMediaSinkServiceEventHandler.hpp")
        val audioApi = projectFile("external/opencardev-aasdk/include/aasdk/Channel/MediaSink/Audio/IAudioMediaSinkServiceEventHandler.hpp")
        val video = projectFile("external/opencardev-aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp")
        val audio = projectFile("external/opencardev-aasdk/src/Channel/MediaSink/Audio/AudioMediaSinkService.cpp")
        val appHeader = projectFile("app/src/main/cpp/jni_session.h")
        val handlerHeader = projectFile("app/src/main/cpp/jni_channel_handlers.h")
        val app = projectFile("app/src/main/cpp/jni_session.cpp")
        val handlers = projectFile("app/src/main/cpp/jni_channel_handlers.cpp")

        assertTrue(ids.contains("MEDIA_MESSAGE_MEDIA_OPTIONS = 32788"))
        assertTrue(videoApi.contains("onMediaOptions"))
        assertTrue(audioApi.contains("onMediaOptions"))
        assertTrue(video.contains("MEDIA_MESSAGE_MEDIA_OPTIONS"))
        assertTrue(audio.contains("MEDIA_MESSAGE_MEDIA_OPTIONS"))
        assertTrue(appHeader.contains("onMediaOptions"))
        assertTrue(handlerHeader.contains("onMediaOptions"))
        assertTrue(app.contains("GAL6 MediaOptions"))
        assertTrue(handlers.contains("GAL6 MediaOptions"))
    }

    @Test
    fun `GAL6 preserves HU-provided status UI ownership in modern UiConfig`() {
        val uiConfig = projectFile("external/opencardev-aasdk/protobuf/aap_protobuf/service/media/shared/message/UiConfig.proto")
        val native = projectFile("app/src/main/cpp/jni_session.cpp")

        assertTrue(uiConfig.contains("repeated int32 hidden_ui_elements = 5"))
        assertTrue(native.contains("add_hidden_ui_elements(1)"))
        assertTrue(native.contains("add_hidden_ui_elements(2)"))
        assertTrue(native.contains("add_hidden_ui_elements(3)"))
    }

    @Test
    fun `GAL6 enriched start envelope fields are parsed and reported`() {
        val startProto = projectFile("external/opencardev-aasdk/protobuf/aap_protobuf/service/media/shared/message/Start.proto")
        val native = projectFile("app/src/main/cpp/jni_session.cpp")

        assertTrue(startProto.contains("optional int32 session_type = 3"))
        assertTrue(startProto.contains("optional bytes media_config = 4"))
        assertTrue(native.contains("session_type="))
        assertTrue(native.contains("media_config_bytes="))
    }

    @Test
    fun `aasdk explicitly accepts GAL5_1 vehicle energy forecast`() {
        val ids = projectFile("external/opencardev-aasdk/protobuf/aap_protobuf/service/navigationstatus/NavigationStatusMessageId.proto")
        val api = projectFile("external/opencardev-aasdk/include/aasdk/Channel/NavigationStatus/INavigationStatusServiceEventHandler.hpp")
        val service = projectFile("external/opencardev-aasdk/src/Channel/NavigationStatus/NavigationStatusService.cpp")
        val handlerHeader = projectFile("app/src/main/cpp/jni_channel_handlers.h")
        val handlers = projectFile("app/src/main/cpp/jni_channel_handlers.cpp")

        assertTrue(ids.contains("INSTRUMENT_CLUSTER_VEHICLE_ENERGY_FORECAST = 32776"))
        assertTrue(api.contains("onVehicleEnergyForecast"))
        assertTrue(service.contains("INSTRUMENT_CLUSTER_VEHICLE_ENERGY_FORECAST"))
        assertTrue(handlerHeader.contains("onVehicleEnergyForecast"))
        assertTrue(handlers.contains("GAL6 VehicleEnergyForecast"))
    }
}
