package com.openautolink.app.cluster

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterBootstrapTaskStateTest {

    @Test
    fun sameGenerationReplacementPreservesPendingBackgroundRequest() {
        val state = ClusterBootstrapTaskState<Any>()
        val first = Any()
        val replacement = Any()
        state.protect(7, first)
        val staleTarget = state.requestBackground(7)!!

        state.protect(7, replacement)

        val replacementTarget = state.pendingTargetFor(replacement)
        assertNotNull(replacementTarget)
        assertSame(replacement, replacementTarget!!.owner)
        assertFalse(state.isCurrent(staleTarget))
        assertSame(replacement, state.requestBackground(7)!!.owner)
    }

    @Test
    fun replacementGenerationDropsOldBackgroundRequest() {
        val state = ClusterBootstrapTaskState<Any>()
        val first = Any()
        val replacement = Any()
        state.protect(7, first)
        state.requestBackground(7)

        state.protect(8, replacement)

        assertNull(state.pendingTargetFor(replacement))
        assertNull(state.requestBackground(7))
    }

    @Test
    fun staleGenerationCannotReplaceCurrentOwnerOrClearPendingRequest() {
        val state = ClusterBootstrapTaskState<Any>()
        val current = Any()
        val stale = Any()
        state.protect(8, current)
        val currentTarget = state.requestBackground(8)!!

        state.protect(7, stale)

        assertSame(current, state.pendingTargetFor(current)!!.owner)
        assertTrue(state.isCurrent(currentTarget))
        assertNull(state.pendingTargetFor(stale))
        assertNull(state.requestBackground(7))
    }

    @Test
    fun destroyedActivityCanBeRecreatedWithinPendingGeneration() {
        val state = ClusterBootstrapTaskState<Any>()
        val first = Any()
        val replacement = Any()
        state.protect(7, first)
        state.requestBackground(7)
        state.destroy(first)

        state.protect(7, replacement)

        assertNotNull(state.pendingTargetFor(replacement))
    }
}
