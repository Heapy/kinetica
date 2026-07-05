package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R04 — recomputeLocked disposes subscriptions BEFORE compute(); a throwing compute
 * detaches the cell.
 *
 * clearSubscriptionsLocked() runs first, then compute() is invoked to collect new
 * dependencies. If compute() throws, the (already disposed) subscriptions are never
 * re-established: the DerivedCell is left with zero subscriptions but its observers
 * still registered. Future dependency changes are then silently missed.
 *
 * Desired/correct behavior (unified DerivedCell design): recomputeLocked must collect
 * new dependencies BEFORE tearing down old subscriptions and must only dispose the old
 * subscriptions after compute() succeeds — so a throwing compute leaves the reactive
 * link intact. This test asserts that correct behavior and therefore FAILS on today's
 * buggy code.
 */
class DerivedCellSurvivesThrowingRecomputeTest {
    @Test
    fun throwingRecomputeMustNotDetachDerivedFromItsSource() {
        val source = store(0)

        // compute throws exactly once — when the source is at the sentinel value 1.
        val computeCount = AtomicInteger(0)
        val derived = DerivedCell(EqualityPolicy.structural()) {
            computeCount.incrementAndGet()
            val v = source.value
            if (v == 1) {
                throw IllegalStateException("boom on sentinel value")
            }
            v * 2
        }

        // Observe the derived. Every notification bumps this counter.
        val notifications = AtomicInteger(0)
        val subscription = derived.observe { notifications.incrementAndGet() }

        // First read activates the cell: compute succeeds and subscribes to `source`.
        assertEquals(0, derived.value)
        assertEquals(0, notifications.get())

        // Drive a dependency change that makes compute() throw. The throw is surfaced
        // through the source write; catch it. On the buggy code this teardown-before-
        // compute path disposes the source subscription and never re-establishes it.
        try {
            source.value = 1
            fail("expected the throwing recompute to propagate")
        } catch (expected: IllegalStateException) {
            // expected — the recompute threw and was surfaced via the source write.
        }

        // The throwing recompute must NOT have notified the observer.
        assertEquals(0, notifications.get())

        // Now change the source again to a non-throwing value. If the reactive link
        // survived the throwing recompute, the derived must recompute AND notify.
        source.value = 2

        // BUG: on today's code the source subscription was disposed during the throwing
        // recompute and never restored, so this notification never arrives.
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
