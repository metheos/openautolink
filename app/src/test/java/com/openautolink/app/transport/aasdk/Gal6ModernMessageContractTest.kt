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
    fun `version response parser bounds checks endian decodes and preserves trailing bytes`() {
        val control = projectFile("external/opencardev-aasdk/src/Channel/Control/ControlServiceChannel.cpp")
        val parser = projectFile("external/opencardev-aasdk/include/aasdk/Channel/Control/VersionResponseParser.hpp")
        val api = projectFile("external/opencardev-aasdk/include/aasdk/Channel/Control/IControlServiceChannelEventHandler.hpp")

        assertTrue(parser.contains("size < VERSION_RESPONSE_PREFIX_SIZE"))
        assertTrue(parser.contains("readBigEndianUint16(data"))
        assertTrue(parser.contains("data + VERSION_RESPONSE_PREFIX_SIZE"))
        assertTrue(parser.contains("size - VERSION_RESPONSE_PREFIX_SIZE"))
        assertTrue(control.contains("parseVersionResponse(payload.cdata"))
        assertTrue(control.contains("onVersionResponseMalformed(payload.size)"))
        assertTrue(api.contains("const common::DataConstBuffer &trailingBytes"))
        assertTrue(api.contains("onVersionResponseMalformed(size_t)"))
    }

    @Test
    fun `modern display always emits four companion inset edges with hidden UI`() {
        val videoConfig = projectFile("app/src/main/cpp/gal_video_policy.h")

        assertTrue(videoConfig.contains("hasHiddenUi"))
        assertTrue(videoConfig.contains("widthMargin > 0 || heightMargin > 0 || hasHiddenUi"))
        assertTrue(videoConfig.contains("margins->set_left(widthMargin / 2)"))
        assertTrue(videoConfig.contains("margins->set_right((widthMargin + 1) / 2)"))
        assertTrue(videoConfig.contains("margins->set_top(heightMargin / 2)"))
        assertTrue(videoConfig.contains("margins->set_bottom((heightMargin + 1) / 2)"))
    }

    @Test
    fun `modern top level codec matches the advertised codec family`() {
        val native = projectFile("app/src/main/cpp/jni_session.cpp")
        val policy = projectFile("app/src/main/cpp/gal_video_policy.h")
        assertTrue(native.contains("const auto selectedVideoCodec"))
        assertTrue(policy.contains("singleVideoCodecFamily ? selectedVideoCodec"))
        assertTrue(native.contains("ms->set_available_type(topLevelVideoCodec)"))
    }

    @Test
    fun `aasdk explicitly accepts typed media options on video and audio`() {
        val ids = projectFile("external/opencardev-aasdk/protobuf/aap_protobuf/service/media/sink/MediaMessageId.proto")
        val mediaOptions = projectFile("external/opencardev-aasdk/protobuf/aap_protobuf/service/media/shared/message/MediaOptions.proto")
        val videoApi = projectFile("external/opencardev-aasdk/include/aasdk/Channel/MediaSink/Video/IVideoMediaSinkServiceEventHandler.hpp")
        val audioApi = projectFile("external/opencardev-aasdk/include/aasdk/Channel/MediaSink/Audio/IAudioMediaSinkServiceEventHandler.hpp")
        val video = projectFile("external/opencardev-aasdk/src/Channel/MediaSink/Video/VideoMediaSinkService.cpp")
        val audio = projectFile("external/opencardev-aasdk/src/Channel/MediaSink/Audio/AudioMediaSinkService.cpp")
        val app = projectFile("app/src/main/cpp/jni_session.cpp")
        val handlers = projectFile("app/src/main/cpp/jni_channel_handlers.cpp")

        assertTrue(ids.contains("MEDIA_MESSAGE_MEDIA_OPTIONS = 32788"))
        assertTrue(mediaOptions.contains("message MediaOptions"))
        assertTrue(videoApi.contains("onMediaOptions"))
        assertTrue(audioApi.contains("onMediaOptions"))
        assertTrue(video.contains("MEDIA_MESSAGE_MEDIA_OPTIONS"))
        assertTrue(audio.contains("MEDIA_MESSAGE_MEDIA_OPTIONS"))
        assertTrue(app.contains("reportMediaOptions(\"video\""))
        assertTrue(handlers.contains("reportMediaOptions(channelName"))
        assertTrue(app.contains("MediaOptions typed"))
    }

    @Test
    fun `opaque GAL payloads remain fully reconstructable below the diagnostic line cap`() {
        val native = projectFile("app/src/main/cpp/jni_session.cpp")
        val header = projectFile("app/src/main/cpp/jni_session.h")

        assertTrue(native.contains("GAL_PAYLOAD_CHUNK_BYTES = 128"))
        assertTrue(header.contains("galEnvelopeSequence_"))
        assertTrue(native.contains("chunk="))
        assertTrue(native.contains("total_chunks="))
        assertTrue(native.contains("offset="))
        assertTrue(native.contains("indication.SerializeToString(&raw)"))
        assertTrue(native.contains("reportGalPayload(channel, \"Start\""))
        assertTrue(native.contains("reportGalPayload(channel, envelope, buffer.cdata, buffer.size)"))
        assertTrue(native.contains("line.size() <= 500"))
    }

    @Test
    fun `modern UiConfig preserves requested status ownership`() {
        val uiConfig = projectFile("external/opencardev-aasdk/protobuf/aap_protobuf/service/media/shared/message/UiConfig.proto")
        val policy = projectFile("app/src/main/cpp/gal_video_policy.h")

        assertTrue(uiConfig.contains("repeated int32 hidden_ui_elements = 5"))
        assertTrue(policy.contains("add_hidden_ui_elements(1)"))
        assertTrue(policy.contains("add_hidden_ui_elements(2)"))
        assertTrue(policy.contains("add_hidden_ui_elements(3)"))
    }

    @Test
    fun `enriched start fields are parsed typed and reported`() {
        val startProto = projectFile("external/opencardev-aasdk/protobuf/aap_protobuf/service/media/shared/message/Start.proto")
        val mediaConfig = projectFile("external/opencardev-aasdk/protobuf/aap_protobuf/service/media/shared/message/MediaConfig.proto")
        val native = projectFile("app/src/main/cpp/jni_session.cpp")

        assertTrue(startProto.contains("optional int32 session_type = 3"))
        assertTrue(startProto.contains("optional bytes media_config = 4"))
        assertTrue(mediaConfig.contains("message MediaConfig"))
        assertTrue(native.contains("session_type="))
        assertTrue(native.contains("MediaConfig typed"))
    }

    @Test
    fun `aasdk explicitly accepts typed GAL5_1 vehicle energy forecast`() {
        val ids = projectFile("external/opencardev-aasdk/protobuf/aap_protobuf/service/navigationstatus/NavigationStatusMessageId.proto")
        val forecast = projectFile("external/opencardev-aasdk/protobuf/aap_protobuf/service/navigationstatus/VehicleEnergyForecast.proto")
        val api = projectFile("external/opencardev-aasdk/include/aasdk/Channel/NavigationStatus/INavigationStatusServiceEventHandler.hpp")
        val service = projectFile("external/opencardev-aasdk/src/Channel/NavigationStatus/NavigationStatusService.cpp")
        val handlerHeader = projectFile("app/src/main/cpp/jni_channel_handlers.h")
        val handlers = projectFile("app/src/main/cpp/jni_channel_handlers.cpp")
        val native = projectFile("app/src/main/cpp/jni_session.cpp")

        assertTrue(ids.contains("INSTRUMENT_CLUSTER_VEHICLE_ENERGY_FORECAST = 32776"))
        assertTrue(forecast.contains("message VehicleEnergyForecastMessage"))
        assertTrue(api.contains("onVehicleEnergyForecast"))
        assertTrue(service.contains("INSTRUMENT_CLUSTER_VEHICLE_ENERGY_FORECAST"))
        assertTrue(handlerHeader.contains("onVehicleEnergyForecast"))
        assertTrue(handlers.contains("reportVehicleEnergyForecast"))
        assertTrue(native.contains("VehicleEnergyForecast typed"))
    }
}
