#include <cassert>

#include "gal_version_policy.h"

int main() {
  using openautolink::jni::gal::versionResponseIsAccepted;
  using openautolink::jni::gal::mediaAckSessionId;

  assert(versionResponseIsAccepted(false, 1, 7, 1, 7, 0, 0));
  assert(versionResponseIsAccepted(false, 6, 1, 1, 7, 0, 0));
  assert(!versionResponseIsAccepted(false, 1, 7, 1, 7, 1, 0));

  assert(!versionResponseIsAccepted(true, 5, 1, 6, 0, 0, 0));
  assert(versionResponseIsAccepted(true, 6, 0, 6, 0, 0, 0));
  assert(versionResponseIsAccepted(true, 6, 1, 6, 0, 0, 0));
  assert(versionResponseIsAccepted(true, 5, 1, 5, 0, 0, 0));
  assert(!versionResponseIsAccepted(true, 4, 3, 5, 0, 0, 0));
  assert(!versionResponseIsAccepted(true, 6, 1, 6, 0, 2, 0));
  assert(mediaAckSessionId(false, 73) == 0);
  assert(mediaAckSessionId(true, 73) == 73);
  return 0;
}
