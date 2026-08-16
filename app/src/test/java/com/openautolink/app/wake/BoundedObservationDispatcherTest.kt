package com.openautolink.app.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedObservationDispatcherTest {

    @Test
    fun `schedule rejection abandons queued work without callbacks and later offer rearms drain`() {
        val scheduled = mutableListOf<() -> Unit>()
        val consumed = mutableListOf<String>()
        val dropped = mutableListOf<Int>()
        val failures = mutableListOf<Throwable>()
        var rejectNextSchedule = true
        val dispatcher = BoundedObservationDispatcher<String>(
            capacity = 2,
            schedule = { drain ->
                if (rejectNextSchedule) {
                    rejectNextSchedule = false
                    throw IllegalStateException("schedule rejected")
                }
                scheduled += drain
            },
            consume = { item -> consumed += item },
            onDropped = { count -> dropped += count },
            onFailure = { error -> failures += error },
        )

        assertFalse(dispatcher.offer("abandoned"))
        assertTrue(scheduled.isEmpty())
        assertTrue(consumed.isEmpty())
        assertTrue(dropped.isEmpty())
        assertTrue(failures.isEmpty())

        assertTrue(dispatcher.offer("second"))
        assertEquals(1, scheduled.size)
        assertTrue(consumed.isEmpty())
        assertTrue(dropped.isEmpty())
        assertTrue(failures.isEmpty())

        scheduled.single().invoke()

        assertEquals(listOf("second"), consumed)
        assertEquals(listOf(1), dropped)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `full queue rejects immediately and reports one drop only from deferred drain`() {
        val scheduled = mutableListOf<() -> Unit>()
        val consumed = mutableListOf<String>()
        val dropped = mutableListOf<Int>()
        val dispatcher = BoundedObservationDispatcher<String>(
            capacity = 1,
            schedule = { drain -> scheduled += drain },
            consume = { item -> consumed += item },
            onDropped = { count -> dropped += count },
            onFailure = { error -> throw AssertionError("Unexpected failure", error) },
        )

        assertTrue(dispatcher.offer("first"))
        assertEquals(1, scheduled.size)
        assertFalse(dispatcher.offer("second"))
        assertEquals(1, scheduled.size)
        assertTrue(consumed.isEmpty())
        assertTrue(dropped.isEmpty())

        scheduled.single().invoke()

        assertEquals(listOf("first"), consumed)
        assertEquals(listOf(1), dropped)
    }

    @Test
    fun `multiple accepted offers schedule one drain and preserve order`() {
        val scheduled = mutableListOf<() -> Unit>()
        val consumed = mutableListOf<Int>()
        val dispatcher = BoundedObservationDispatcher<Int>(
            capacity = 3,
            schedule = { drain -> scheduled += drain },
            consume = { item -> consumed += item },
            onDropped = { count -> throw AssertionError("Unexpected $count dropped") },
            onFailure = { error -> throw AssertionError("Unexpected failure", error) },
        )

        assertTrue(dispatcher.offer(1))
        assertTrue(dispatcher.offer(2))
        assertTrue(dispatcher.offer(3))
        assertEquals(1, scheduled.size)
        assertTrue(consumed.isEmpty())

        scheduled.single().invoke()

        assertEquals(listOf(1, 2, 3), consumed)
    }

    @Test
    fun `consumer failure is reported from drain and later items are still consumed`() {
        val scheduled = mutableListOf<() -> Unit>()
        val consumed = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val dispatcher = BoundedObservationDispatcher<String>(
            capacity = 2,
            schedule = { drain -> scheduled += drain },
            consume = { item ->
                if (item == "bad") error("consume failed")
                consumed += item
            },
            onDropped = { count -> throw AssertionError("Unexpected $count dropped") },
            onFailure = { error -> failures += error },
        )

        assertTrue(dispatcher.offer("bad"))
        assertTrue(dispatcher.offer("good"))
        assertTrue(consumed.isEmpty())
        assertTrue(failures.isEmpty())

        scheduled.single().invoke()

        assertEquals(listOf("good"), consumed)
        assertEquals(listOf("consume failed"), failures.map(Throwable::message))
    }

    @Test
    fun `offer after an empty drain schedules a new drain`() {
        val scheduled = mutableListOf<() -> Unit>()
        val consumed = mutableListOf<String>()
        val dispatcher = BoundedObservationDispatcher<String>(
            capacity = 1,
            schedule = { drain -> scheduled += drain },
            consume = { item -> consumed += item },
            onDropped = { count -> throw AssertionError("Unexpected $count dropped") },
            onFailure = { error -> throw AssertionError("Unexpected failure", error) },
        )

        assertTrue(dispatcher.offer("first"))
        scheduled.single().invoke()
        assertEquals(listOf("first"), consumed)

        assertTrue(dispatcher.offer("second"))
        assertEquals(2, scheduled.size)
        scheduled.last().invoke()

        assertEquals(listOf("first", "second"), consumed)
    }
}
