package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class UnobservedDerivedReadDoesNotLeakTest {
    @Test
    fun unobservedPeekReadDoesNotSubscribeDerivedToItsSource() {
        val computeCount = AtomicInteger(0)
        val source = store(0)
        val derived = DerivedCell(EqualityPolicy.structural()) {
            computeCount.incrementAndGet()
            source.value * 2
        }

        val value = peek { derived.value }
        assertEquals(0, value, "sanity: derived should compute source * 2")

        val computesAfterRead = computeCount.get()

        repeat(5) { i -> source.value = i + 1 }

        assertEquals(
            computesAfterRead,
            computeCount.get(),
            "Unobserved peek{ derived.value } must not subscribe the derived cell to its source; " +
                "each source write eagerly recomputed the unobserved derived cell " +
                "(${computeCount.get() - computesAfterRead} leaked recomputes), proving the " +
                "source retained a listener on the derived cell forever.",
        )
    }

    @Test
    fun unobservedBareReadDoesNotSubscribeDerivedToItsSource() {
        val computeCount = AtomicInteger(0)
        val source = store(0)
        val derived = DerivedCell(EqualityPolicy.structural()) {
            computeCount.incrementAndGet()
            source.value * 2
        }

        val value = derived.value
        assertEquals(0, value, "sanity: derived should compute source * 2")

        val computesAfterRead = computeCount.get()

        repeat(5) { i -> source.value = i + 1 }

        assertEquals(
            computesAfterRead,
            computeCount.get(),
            "Unobserved derived.value read must not subscribe to its source; " +
                "each source write eagerly recomputed the unobserved derived cell " +
                "(${computeCount.get() - computesAfterRead} leaked recomputes).",
        )
    }
}
