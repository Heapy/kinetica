package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * R13 — DerivedCell.listeners is a mutableSetOf<() -> Unit>(). Registering the SAME
 * listener instance twice via observe() collapses to a single Set entry, so disposing
 * the first Disposable removes that entry, empties the set, and tears down all source
 * subscriptions — silencing the second, still-live observer.
 *
 * This test asserts the CORRECT behavior: two independent observe() registrations of the
 * same listener instance must be independent, so disposing one must leave the other live
 * and the cell still subscribed to its source. It FAILS on today's Set-backed code.
 */
class DerivedCellDuplicateListenerDisposalTest {
    @Test
    fun disposingOneOfTwoIdenticalListenersKeepsTheOtherLive() {
        val source = store(1)
        val derived = DerivedCell(EqualityPolicy.structural()) {
            source.value * 2
        }

        val fires = AtomicInteger(0)
        // A single shared, non-capturing listener instance registered twice.
        val listener: () -> Unit = { fires.incrementAndGet() }

        val handle1 = derived.observe(listener)
        val handle2 = derived.observe(listener)

        // Read value to establish the subscription to `source`.
        require(derived.value == 2)

        // Dispose only the FIRST handle. handle2 (same instance) must remain live.
        handle1.dispose()

        // Mutate the source dependency; the still-live observer must be notified.
        source.value = 5

        assertTrue(
            fires.get() >= 1,
            "After disposing handle1, the still-live handle2 observer must still fire on " +
                "a source write, but it fired ${fires.get()} times — disposing one " +
                "identity-equal listener silenced the other and tore down subscriptions.",
        )

        // The cell must still be reactively subscribed: a second write also notifies.
        val firesBeforeSecondWrite = fires.get()
        source.value = 9
        assertTrue(
            fires.get() > firesBeforeSecondWrite,
            "The derived cell must remain subscribed to its source after disposing one " +
                "of two identical listeners.",
        )

        handle2.dispose()
    }
}
