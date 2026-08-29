package com.openautolink.app.cluster

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterNavigationLifecyclePolicyTest {

    @Test
    fun firstRouteStartsNavigationAndPublishesTrip() {
        val policy = ClusterNavigationLifecyclePolicy()

        assertEquals(
            ClusterNavigationLifecyclePolicy.Action.START_AND_UPDATE,
            policy.onRouteAvailable(),
        )
    }

    @Test
    fun activeRouteUpdatesWithoutRestartingNavigation() {
        val policy = ClusterNavigationLifecyclePolicy()
        policy.onRouteAvailable()

        assertEquals(
            ClusterNavigationLifecyclePolicy.Action.UPDATE,
            policy.onRouteAvailable(),
        )
    }

    @Test
    fun clearingActiveRouteEndsNavigation() {
        val policy = ClusterNavigationLifecyclePolicy()
        policy.onRouteAvailable()

        assertEquals(
            ClusterNavigationLifecyclePolicy.Action.END,
            policy.onRouteCleared(),
        )
        assertEquals(
            ClusterNavigationLifecyclePolicy.Action.NONE,
            policy.onRouteCleared(),
        )
    }

    @Test
    fun hostStopSuppressesCurrentRouteUntilItClears() {
        val policy = ClusterNavigationLifecyclePolicy()
        policy.onRouteAvailable()

        policy.onHostStop()
        assertEquals(
            ClusterNavigationLifecyclePolicy.Action.NONE,
            policy.onRouteAvailable(),
        )
        assertEquals(
            ClusterNavigationLifecyclePolicy.Action.NONE,
            policy.onRouteCleared(),
        )
        assertEquals(
            ClusterNavigationLifecyclePolicy.Action.START_AND_UPDATE,
            policy.onRouteAvailable(),
        )
    }

    @Test
    fun arrivalEndsOnceAndSuppressesTerminalRouteUntilClear() {
        val policy = ClusterNavigationLifecyclePolicy()
        policy.onRouteAvailable()

        assertEquals(
            ClusterNavigationLifecyclePolicy.Action.END,
            policy.onRouteTerminated(),
        )
        assertEquals(
            ClusterNavigationLifecyclePolicy.Action.NONE,
            policy.onRouteAvailable(),
        )
        assertEquals(
            ClusterNavigationLifecyclePolicy.Action.NONE,
            policy.onRouteTerminated(),
        )
    }

    @Test
    fun failedNavigationStartAllowsNextUpdateToRetryStart() {
        val policy = ClusterNavigationLifecyclePolicy()
        policy.onRouteAvailable()

        policy.onNavigationStartFailed()

        assertEquals(
            ClusterNavigationLifecyclePolicy.Action.START_AND_UPDATE,
            policy.onRouteAvailable(),
        )
    }
}
