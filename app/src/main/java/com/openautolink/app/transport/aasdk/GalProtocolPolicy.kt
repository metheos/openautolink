package com.openautolink.app.transport.aasdk

/** Raw GAL protocol version requested by the head unit before TLS. */
data class GalProtocolVersion(val major: Int, val minor: Int) : Comparable<GalProtocolVersion> {
    override fun compareTo(other: GalProtocolVersion): Int =
        compareValuesBy(this, other, GalProtocolVersion::major, GalProtocolVersion::minor)

    override fun toString(): String = "$major.$minor"
}

/** Cumulative behavior selected solely from the raw HU-requested GAL version. */
data class GalProtocolConfig(
    val requestedVersion: GalProtocolVersion,
    val maxUnacked: Int,
    val requireMinimumCompatibleResponse: Boolean,
    val modernDisplayPolicy: Boolean,
    val sendAudioAcks: Boolean,
    val singleVideoCodecFamily: Boolean,
    val useActiveMediaSessionIds: Boolean,
    val hevcKeyframeIntervalSeconds: Int,
)

object GalProtocolPolicy {
    private val GAL_1_7 = GalProtocolVersion(1, 7)
    private val GAL_4_3 = GalProtocolVersion(4, 3)
    private val GAL_5_0 = GalProtocolVersion(5, 0)
    private val GAL_5_1 = GalProtocolVersion(5, 1)
    private val GAL_6_0 = GalProtocolVersion(6, 0)

    val supportedVersions: List<String> = listOf(GAL_1_7, GAL_4_3, GAL_5_0, GAL_5_1, GAL_6_0)
        .map(GalProtocolVersion::toString)

    private val versionsByName = supportedVersions.zip(
        listOf(GAL_1_7, GAL_4_3, GAL_5_0, GAL_5_1, GAL_6_0),
    ).toMap()

    fun forVersion(configuredVersion: String): GalProtocolConfig {
        val requested = versionsByName[configuredVersion] ?: GAL_1_7
        return GalProtocolConfig(
            requestedVersion = requested,
            maxUnacked = 30,
            requireMinimumCompatibleResponse = requested >= GAL_4_3,
            modernDisplayPolicy = requested >= GAL_4_3,
            sendAudioAcks = requested < GAL_5_0,
            singleVideoCodecFamily = requested >= GAL_5_0,
            useActiveMediaSessionIds = requested >= GAL_4_3,
            hevcKeyframeIntervalSeconds = if (requested >= GAL_6_0) 2 else 60,
        )
    }

    /** Resolve the new string setting with a one-way fallback to the former Boolean. */
    fun resolvePersistedVersion(configuredVersion: String?, legacyEnabled: Boolean?): String =
        when {
            configuredVersion in supportedVersions -> configuredVersion!!
            legacyEnabled == true -> "6.0"
            else -> "1.7"
        }

    fun expectedHevcGopFrames(configuredVersion: String, advertisedFps: Int): Int =
        forVersion(configuredVersion).hevcKeyframeIntervalSeconds * advertisedFps.coerceAtLeast(0)
}
