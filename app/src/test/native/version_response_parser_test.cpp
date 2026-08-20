#include <array>
#include <cassert>
#include <cstdint>

#include <aasdk/Channel/Control/VersionResponseParser.hpp>

int main() {
  using namespace aasdk::channel::control;
  const std::array<uint8_t, 9> accepted{0x00, 0x06, 0x00, 0x01, 0x00, 0x00,
                                         0xaa, 0xbb, 0xcc};
  ParsedVersionResponse parsed;
  assert(parseVersionResponse(accepted.data(), accepted.size(), parsed));
  assert(parsed.major == 6 && parsed.minor == 1 && parsed.status == 0);
  assert(parsed.trailingSize == 3);
  assert(parsed.trailingData[0] == 0xaa && parsed.trailingData[2] == 0xcc);

  for (size_t size = 0; size < VERSION_RESPONSE_PREFIX_SIZE; ++size) {
    ParsedVersionResponse truncated;
    assert(!parseVersionResponse(accepted.data(), size, truncated));
  }

  const std::array<uint8_t, 6> rejected{0x00, 0x06, 0x00, 0x00, 0xff, 0xff};
  ParsedVersionResponse failure;
  assert(parseVersionResponse(rejected.data(), rejected.size(), failure));
  assert(failure.status == -1);
  return 0;
}
