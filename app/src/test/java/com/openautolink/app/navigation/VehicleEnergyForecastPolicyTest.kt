package com.openautolink.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleEnergyForecastPolicyTest {
    private fun forecast(
        arrivalWh: Int = 35_700,
        quality: Int = VehicleEnergyForecast.QUALITY_HIGH,
        receivedAtMs: Long = 1_000L,
    ) = VehicleEnergyForecast(
        energyAtNextStop = EnergyAtDistance(
            distanceMeters = 100_000,
            arrivalBatteryEnergyWh = arrivalWh,
            timeToArrivalSeconds = 3_600,
        ),
        distanceToEmpty = EnergyAtDistance(250_000, 0, 9_000),
        forecastQuality = quality,
        nextChargingStop = ChargingStationDetails(20_000, 190_000, 1_200),
        receivedAtElapsedMs = receivedAtMs,
    )

    @Test
    fun `arrival percentage uses Maps energy and the VEM capacity snapshot`() {
        assertEquals(42, VehicleEnergyForecastPolicy.arrivalPercent(forecast(), 85_000))
    }

    @Test
    fun `arrival percentage rejects missing or invalid energy`() {
        assertNull(VehicleEnergyForecastPolicy.arrivalPercent(forecast(arrivalWh = -1), 85_000))
        assertNull(VehicleEnergyForecastPolicy.arrivalPercent(forecast(), 0))
        assertNull(VehicleEnergyForecastPolicy.arrivalPercent(null, 85_000))
    }

    @Test
    fun `arrival percentage clamps malformed over capacity forecasts`() {
        assertEquals(100, VehicleEnergyForecastPolicy.arrivalPercent(forecast(arrivalWh = 100_000), 85_000))
    }

    @Test
    fun `cluster text distinguishes high and lower confidence forecasts`() {
        assertEquals("Arrive 42%", VehicleEnergyForecastPolicy.tripText(forecast(), 85_000))
        assertEquals(
            "Arrive ~42%",
            VehicleEnergyForecastPolicy.tripText(
                forecast(quality = VehicleEnergyForecast.QUALITY_LOW),
                85_000,
            ),
        )
    }

    @Test
    fun `forecast freshness is bounded and monotonic`() {
        val value = forecast(receivedAtMs = 1_000L)
        assertTrue(VehicleEnergyForecastPolicy.isFresh(value, nowElapsedMs = 120_999L))
        assertFalse(VehicleEnergyForecastPolicy.isFresh(value, nowElapsedMs = 121_001L))
        assertFalse(VehicleEnergyForecastPolicy.isFresh(value, nowElapsedMs = 999L))
    }
}
