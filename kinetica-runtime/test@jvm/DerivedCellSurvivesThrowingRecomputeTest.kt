package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class DerivedCellSurvivesThrowingRecomputeTest {
    @Test
    fun throwingRecomputeMustNotDetachDerivedFromItsSource() {
        val source = store(0)

        val computeCount = AtomicInteger(0)
        val derived = DerivedCell(EqualityPolicy.structural()) {
            computeCount.incrementAndGet()
            val v = source.value
            if (v == 1) {
                throw IllegalStateException("boom on sentinel value")
            }
            v * 2
        }

        val notifications = AtomicInteger(0)
        val subscription = derived.observe { notifications.incrementAndGet() }

        assertEquals(0, derived.value)
        assertEquals(0, notifications.get())

        try {
            source.value = 1
            fail("expected the throwing recompute to propagate")
        } catch (expected: IllegalStateException) {
        }

        assertEquals(0, notifications.get())

        source.value = 2

        assertTrue(
            notifications.get() >= 1,
            "derived must notify its observer after a post-throw dependency change " +
                "(reactive link must survive a throwing recompute); notifications=" +
                notifications.get(),
        )
        assertEquals(4, derived.value)
        subscription.dispose()
    }
}
