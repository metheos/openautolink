package com.openautolink.companion.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarNetworkBindingPolicyTest {

    @Test
    fun `explicit companion network wins even when it advertises internet`() {
        val selector = CarNetworkSelector<String>()
        selector.observe("home", hasInternet = true, hasUsableIpv4 = true, correlatedWithWpp = false)
        selector.observe("wpp-local", hasInternet = false, hasUsableIpv4 = true, correlatedWithWpp = true)
        selector.observe("configured-car", hasInternet = true, hasUsableIpv4 = true, correlatedWithWpp = false)

        selector.prefer("configured-car")

        assertEquals("configured-car", selector.selectedNetwork)
    }

    @Test
    fun `wpp correlated local only wifi is selected without configured car entries`() {
        val selector = CarNetworkSelector<String>()
        selector.observe("home", hasInternet = true, hasUsableIpv4 = true, correlatedWithWpp = false)
        selector.observe("wpp-local", hasInternet = false, hasUsableIpv4 = true, correlatedWithWpp = true)

        assertEquals("wpp-local", selector.selectedNetwork)
    }

    @Test
    fun `internet wifi and unaddressed wifi are not inferred as the car network`() {
        val selector = CarNetworkSelector<String>()
        selector.observe("home", hasInternet = true, hasUsableIpv4 = true, correlatedWithWpp = false)
        selector.observe("not-addressed-yet", hasInternet = false, hasUsableIpv4 = false, correlatedWithWpp = true)

        assertNull(selector.selectedNetwork)
    }

    @Test
    fun `unrelated local only wifi is not inferred as WPP`() {
        val selector = CarNetworkSelector<String>()

        selector.observe(
            "wifi-direct",
            hasInternet = false,
            hasUsableIpv4 = true,
            correlatedWithWpp = false,
        )

        assertNull(selector.selectedNetwork)
    }

    @Test
    fun `current eligible network remains stable when another local network appears`() {
        val selector = CarNetworkSelector<String>()
        selector.observe("first", hasInternet = false, hasUsableIpv4 = true, correlatedWithWpp = true)
        selector.observe("second", hasInternet = false, hasUsableIpv4 = true, correlatedWithWpp = true)

        assertEquals("first", selector.selectedNetwork)
    }

    @Test
    fun `losing the selected network falls through to another eligible network`() {
        val selector = CarNetworkSelector<String>()
        selector.observe("first", hasInternet = false, hasUsableIpv4 = true, correlatedWithWpp = true)
        selector.observe("second", hasInternet = false, hasUsableIpv4 = true, correlatedWithWpp = true)

        selector.lost("first")

        assertEquals("second", selector.selectedNetwork)
    }

    @Test
    fun `clearing an explicit preference falls back to local only discovery`() {
        val selector = CarNetworkSelector<String>()
        selector.observe("wpp-local", hasInternet = false, hasUsableIpv4 = true, correlatedWithWpp = true)
        selector.observe("configured-car", hasInternet = true, hasUsableIpv4 = true, correlatedWithWpp = false)
        selector.prefer("configured-car")

        selector.prefer(null)

        assertEquals("wpp-local", selector.selectedNetwork)
    }

    @Test
    fun `initial unbound listener receives a binding generation`() {
        val generations = ListenerBindingGenerations<String>()

        val initial = generations.replaceWith(null)

        assertEquals(1L, initial?.generation)
        assertTrue(generations.owns(requireNotNull(initial)))
    }

    @Test
    fun `listener generations ignore duplicates and reject stale completion`() {
        val generations = ListenerBindingGenerations<String>()

        val first = generations.replaceWith("wpp-local")
        val duplicate = generations.replaceWith("wpp-local")
        val second = generations.replaceWith("configured-car")

        assertEquals(1L, first?.generation)
        assertNull(first?.previousTarget)
        assertNull(duplicate)
        assertEquals(2L, second?.generation)
        assertEquals("wpp-local", second?.previousTarget)
        assertFalse(generations.owns(requireNotNull(first)))
        assertTrue(generations.owns(requireNotNull(second)))
    }

    @Test
    fun `listener stop invalidates in flight binding without requesting another bind`() {
        val generations = ListenerBindingGenerations<String>()
        val active = requireNotNull(generations.replaceWith("wpp-local"))

        generations.stop()

        assertFalse(generations.owns(active))
        assertNull(generations.replaceWith(null))
    }
}
