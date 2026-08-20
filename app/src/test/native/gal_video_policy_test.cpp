#include <cassert>
#include <string>

#include "gal_video_policy.h"

int main() {
  using aap_protobuf::service::media::sink::message::VideoConfiguration;
  using openautolink::jni::gal::applyVideoMarginsAndHiddenUi;
  using openautolink::jni::gal::selectTopLevelVideoCodec;

  VideoConfiguration legacy;
  assert(!applyVideoMarginsAndHiddenUi(&legacy, 0, 0, false, true, true, true));
  assert(!legacy.has_ui_config());

  VideoConfiguration modernZero;
  assert(applyVideoMarginsAndHiddenUi(&modernZero, 0, 0, true, true, false, false));
  assert(modernZero.has_ui_config());
  assert(modernZero.ui_config().has_margins());
  const auto& zero = modernZero.ui_config().margins();
  assert(zero.has_left() && zero.left() == 0);
  assert(zero.has_right() && zero.right() == 0);
  assert(zero.has_top() && zero.top() == 0);
  assert(zero.has_bottom() && zero.bottom() == 0);
  assert(modernZero.ui_config().hidden_ui_elements_size() == 1);
  assert(modernZero.ui_config().hidden_ui_elements(0) == 1);

  std::string wire;
  assert(modernZero.SerializeToString(&wire));
  const std::string expectedZeroInsetWire(
      "\x5a\x0c\x0a\x08\x08\x00\x10\x00\x18\x00\x20\x00\x28\x01", 14);
  assert(wire == expectedZeroInsetWire);
  VideoConfiguration roundTrip;
  assert(roundTrip.ParseFromString(wire));
  assert(roundTrip.ui_config().margins().has_left());
  assert(roundTrip.ui_config().margins().has_right());
  assert(roundTrip.ui_config().margins().has_top());
  assert(roundTrip.ui_config().margins().has_bottom());

  VideoConfiguration nonZero;
  assert(!applyVideoMarginsAndHiddenUi(&nonZero, 5, 3, false, false, false, false));
  const auto& split = nonZero.ui_config().margins();
  assert(split.left() == 2 && split.right() == 3);
  assert(split.top() == 1 && split.bottom() == 2);

  assert(selectTopLevelVideoCodec(true, 7, 1) == 7);
  assert(selectTopLevelVideoCodec(false, 7, 1) == 1);
  return 0;
}
