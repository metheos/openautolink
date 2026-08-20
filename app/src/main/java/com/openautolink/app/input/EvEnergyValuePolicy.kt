package com.openautolink.app.input

/** Unit and provenance rules for EV values crossing from VHAL into Maps' VEM. */
object EvEnergyValuePolicy {
    private const val DEFAULT_MAX_CHARGE_POWER_W = 150_000

    /** AOSP defines EV_BATTERY_INSTANTANEOUS_CHARGE_RATE in milliwatts. */
    fun instantaneousMilliwattsToWatts(valueMilliwatts: Float?): Float? =
        valueMilliwatts?.div(1_000f)

    /** Maximum capability comes from a vehicle profile, never a live pack-flow sample. */
    fun maxChargePowerWatts(profileMaximumWatts: Int?): Int =
        profileMaximumWatts?.takeIf { it > 0 } ?: DEFAULT_MAX_CHARGE_POWER_W
}
