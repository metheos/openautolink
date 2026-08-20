package com.openautolink.app.transport.aasdk

/** Raw GAL protocol version requested by the head unit before TLS. */
data class GalProtocolVersion(val major: Int, val minor: Int)

/**
 * Behavior selected by the experimental GAL 6 compatibility toggle.
 *
 * GAL 6.0 is not just a version label: Gearhead switches audio to ackless,
 * enriches AV start envelopes, and selects the HEVC encoder's short (2-second)
 * keyframe interval. The setup window remains OAL's proven value of 30.
 */
data class GalProtocolConfig(
    val requestedVersion: GalProtocolVersion,
    val maxUnacked: Int,
    val sendAudioAcks: Boolean,
    val hevcKeyframeIntervalSeconds: Int,
)

object GalProtocolPolicy {
    private const val LEGACY_KEYFRAME_INTERVAL_SECONDS = 60
    private const val GAL6_KEYFRAME_INTERVAL_SECONDS = 2

    fun forExperimentalGal6(enabled: Boolean): GalProtocolConfig =
        if (enabled) {
            GalProtocolConfig(
                requestedVersion = GalProtocolVersion(6, 0),
                // The audited GAL gates require only a positive setup value;
                // ackless audio does not consume this as per-buffer credit.
                maxUnacked = 30,
                sendAudioAcks = false,
                hevcKeyframeIntervalSeconds = GAL6_KEYFRAME_INTERVAL_SECONDS,
            )
        } else {
            GalProtocolConfig(
                requestedVersion = GalProtocolVersion(1, 7),
                maxUnacked = 30,
                sendAudioAcks = true,
                hevcKeyframeIntervalSeconds = LEGACY_KEYFRAME_INTERVAL_SECONDS,
            )
        }

    /**
     * Expected OMX HEVC GOP frame count.
     *
     * AOSP converts I-frame seconds to frames using configured fps. This is a
     * diagnostic prediction, not a guarantee for every vendor encoder.
     */
    fun expectedHevcGopFrames(experimentalGal6: Boolean, advertisedFps: Int): Int {
        return forExperimentalGal6(experimentalGal6).hevcKeyframeIntervalSeconds *
            advertisedFps.coerceAtLeast(0)
    }
}
