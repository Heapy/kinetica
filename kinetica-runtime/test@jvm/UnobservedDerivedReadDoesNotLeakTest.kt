package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R03 — Reading .value on an UNOBSERVED derived cell subscribes it permanently (leak) + eager
 * waste; peek{} does not prevent it.
 *
 * Buggy behavior: a bare .value read (or one wrapped in peek{}) with no observer runs
 * recomputeLocked(), which registers a listener on every source cell. Nothing tears those
 * listeners down (cleanup only fires when the last observer disposes), so the source retains the
 * derived cell forever and re-notifies it on every subsequent write — driving an eager recompute
 * per write even though nobody is observing.
 *
 * Desired behavior (asserted here, currently FAILS): an unobserved .value read recomputes on
 * demand but does NOT subscribe to its sources. With no observer, later source writes must not
 * reach the derived cell, so compute() must run exactly ONCE (for the on-demand read) regardless
 * of how many times the source is written afterward.
 */
class UnobservedDerivedReadDoesNotLeakTest {
    @Test
    fun unobservedPeekReadDoesNotSubscribeDerivedToItsSource() {
        val computeCount = AtomicInteger(0)
        val source = store(0)
        val derived = DerivedCell(EqualityPolicy.structural()) {
            computeCount.incrementAndGet()
            source.value * 2
        }

        // Unobserved read via peek {} — peek is meant to suppress reactive coupling, and there is
        // no observer at all. This must recompute on demand WITHOUT registering a source listener.
        val value = peek { derived.value }
        assertEquals(0, value, "sanity: derived should compute source * 2")

        val computesAfterRead = computeCount.get()

        // Write the source several times. With no observer, none of these must reach the derived.
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

        // Bare read, no observer, no peek.
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
