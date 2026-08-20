package com.openautolink.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvEnergyValuePolicyTest {
    @Test
    fun `instantaneous VHAL battery power converts milliwatts to watts`() {
        assertEquals(35_500f, EvEnergyValuePolicy.instantaneousMilliwattsToWatts(35_500_000f))
        assertEquals(-2_500f, EvEnergyValuePolicy.instantaneousMilliwattsToWatts(-2_500_000f))
        assertNull(EvEnergyValuePolicy.instantaneousMilliwattsToWatts(null))
    }

    @Test
    fun `maximum charging capability never comes from instantaneous pack power`() {
        assertEquals(190_000, EvEnergyValuePolicy.maxChargePowerWatts(190_000))
        assertEquals(150_000, EvEnergyValuePolicy.maxChargePowerWatts(null))
        assertEquals(150_000, EvEnergyValuePolicy.maxChargePowerWatts(0))
    }
}
