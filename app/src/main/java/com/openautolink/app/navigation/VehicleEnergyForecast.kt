package com.openautolink.app.navigation

import kotlin.math.roundToInt

/** Route-aware energy result calculated by Google Maps and returned over GAL 5.1+. */
data class VehicleEnergyForecast(
    val energyAtNextStop: EnergyAtDistance? = null,
    val distanceToEmpty: EnergyAtDistance? = null,
    val forecastQuality: Int = QUALITY_UNKNOWN,
    val nextChargingStop: ChargingStationDetails? = null,
    val receivedAtElapsedMs: Long,
) {
    companion object {
        const val QUALITY_UNKNOWN = 0
        const val QUALITY_LOW = 1
        const val QUALITY_HIGH = 2
    }
}

data class EnergyAtDistance(
    val distanceMeters: Int,
    val arrivalBatteryEnergyWh: Int,
    val timeToArrivalSeconds: Int,
)

data class ChargingStationDetails(
    val minimumDepartureEnergyWh: Int,
    val maximumRatedPowerWatts: Int,
    val estimatedChargingTimeSeconds: Int,
)

/** Formatting and validity rules shared by projection, diagnostics, and cluster output. */
object VehicleEnergyForecastPolicy {
    const val MAX_AGE_MS = 120_000L

    fun arrivalPercent(forecast: VehicleEnergyForecast?, batteryCapacityWh: Int): Int? {
        val arrivalWh = forecast?.energyAtNextStop?.arrivalBatteryEnergyWh ?: return null
        if (arrivalWh < 0 || batteryCapacityWh <= 0) return null
        return (arrivalWh.toDouble() / batteryCapacityWh.toDouble() * 100.0)
            .roundToInt()
            .coerceIn(0, 100)
    }

    fun tripText(forecast: VehicleEnergyForecast?, batteryCapacityWh: Int): String? {
        val percent = arrivalPercent(forecast, batteryCapacityWh) ?: return null
        val approximate = forecast?.forecastQuality != VehicleEnergyForecast.QUALITY_HIGH
        return if (approximate) "Arrive ~$percent%" else "Arrive $percent%"
    }

    fun isFresh(forecast: VehicleEnergyForecast?, nowElapsedMs: Long): Boolean {
        forecast ?: return false
        val age = nowElapsedMs - forecast.receivedAtElapsedMs
        return age in 0..MAX_AGE_MS
    }
}
