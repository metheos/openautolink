#pragma once

#include <aap_protobuf/service/media/sink/message/VideoConfiguration.pb.h>

namespace openautolink::jni::gal {

template <typename CodecType>
CodecType selectTopLevelVideoCodec(
    bool singleVideoCodecFamily,
    CodecType selectedVideoCodec,
    CodecType legacyVideoCodec) {
    return singleVideoCodecFamily ? selectedVideoCodec : legacyVideoCodec;
}

inline bool applyVideoMarginsAndHiddenUi(
    aap_protobuf::service::media::sink::message::VideoConfiguration* videoConfig,
    int widthMargin,
    int heightMargin,
    bool modernDisplayPolicy,
    bool hideClock,
    bool hideBattery,
    bool hideSignal) {
    if (videoConfig == nullptr) return false;

    const bool hasHiddenUi = modernDisplayPolicy &&
        (hideClock || hideBattery || hideSignal);
    if (widthMargin > 0 || heightMargin > 0 || hasHiddenUi) {
        auto* ui = videoConfig->mutable_ui_config();
        auto* margins = ui->mutable_margins();
        margins->set_left(widthMargin / 2);
        margins->set_right((widthMargin + 1) / 2);
        margins->set_top(heightMargin / 2);
        margins->set_bottom((heightMargin + 1) / 2);
    }

    if (hasHiddenUi) {
        auto* ui = videoConfig->mutable_ui_config();
        if (hideClock) ui->add_hidden_ui_elements(1);
        if (hideBattery) ui->add_hidden_ui_elements(2);
        if (hideSignal) ui->add_hidden_ui_elements(3);
    }
    return hasHiddenUi;
}

} // namespace openautolink::jni::gal
