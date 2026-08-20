#pragma once

#include <cstdint>

namespace openautolink::jni::gal {

inline bool reportedVersionIsAtLeastRequested(
    uint16_t reportedMajor,
    uint16_t reportedMinor,
    int requestedMajor,
    int requestedMinor) {
    return reportedMajor > requestedMajor ||
        (reportedMajor == requestedMajor && reportedMinor >= requestedMinor);
}

inline bool versionResponseIsAccepted(
    bool requireMinimumCompatibleResponse,
    uint16_t reportedMajor,
    uint16_t reportedMinor,
    int requestedMajor,
    int requestedMinor,
    int rawStatus,
    int successStatus) {
    if (rawStatus != successStatus) return false;
    return !requireMinimumCompatibleResponse ||
        reportedVersionIsAtLeastRequested(
            reportedMajor, reportedMinor, requestedMajor, requestedMinor);
}

inline int mediaAckSessionId(bool useActiveSessionId, int activeSessionId) {
    return useActiveSessionId ? activeSessionId : 0;
}

} // namespace openautolink::jni::gal
