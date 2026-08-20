package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

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

        assertEquals("x", c.value)
        cCompute.set(0)
        notifications.set(0)

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
