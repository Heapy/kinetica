package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Prune-unchanged — an intermediate derived whose recomputed value does NOT change must not
 * propagate a notification to its downstream observer.
 *
 * Topology: S -> A(= S > 0) -> C(= if (A) "x" else "y"). Writing S from 1 to 2 changes S but
 * leaves A's value (true) unchanged, so A's version does not advance; C's dependency versions stay
 * consistent, C is not recomputed, and C's post version equals its pre version — so C's observer
 * is NOT notified. This proves the wave prunes an unchanged intermediate (no over-notification).
 */
class DerivedCellPrunesUnchangedIntermediateTest {
    @Test
    fun unchangedIntermediateDoesNotNotifyDownstreamObserver() {
        val s = store(1)
        val a = DerivedCell(EqualityPolicy.structural()) { s.value > 0 }
        val cCompute = AtomicInteger(0)
        val c = DerivedCell(EqualityPolicy.structural()) {
            cCompute.incrementAndGet()
            if (a.value) "x" else "y"
        }

        val notifications = AtomicInteger(0)
        val sub = c.observe { notifications.incrementAndGet() }

        // Establish the initial consistent state, then measure ONLY the write.
        assertEquals("x", c.value)
        cCompute.set(0)
        notifications.set(0)

        // Write S 1 -> 2: S changes, but A (= S > 0) stays true, so C must not be recomputed/notified.
        s.value = 2

        sub.dispose()

        assertEquals(
            0,
            notifications.get(),
            "an unchanged intermediate (A: 1>0 == 2>0 == true) must be pruned; C's observer " +
                "must NOT be notified, but it fired ${notifications.get()} times",
        )
        assertEquals("x", c.value, "C's value must remain the consistent 'x'")
    }
}
