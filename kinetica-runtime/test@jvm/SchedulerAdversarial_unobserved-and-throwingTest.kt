package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Adversarial mix against the new mark/flush scheduler ([PropagationWave]). Each scenario tries to
 * break a DIFFERENT invariant that the two-phase (MARK / RECOMPUTE+PRUNE / NOTIFY) wave must
 * preserve, and records EVERY value / notification each observer sees so that a glitch (mixed
 * old/new value), a missed notification, or an extra notification fails the test.
 *
 *  (a) An UNOBSERVED derived read must not subscribe/leak, and must still return the CORRECT value
 *      after later source writes (recompute-on-demand + version snapshot).
 *  (b) A compute() that throws ONCE must not detach an OBSERVED derived: the reactive link survives
 *      the transient error and later writes still notify EXACTLY once with the correct value.
 *  (c) A listener that throws must not suppress a SIBLING observer notified in the SAME flush —
 *      neither a sibling on the same cell nor an independent observer of a different cell reached by
 *      the same source write.
 *
 * Single-threaded and deterministic: no sleeps, no background threads.
 */
class `SchedulerAdversarial_unobserved-and-throwingTest` {

    // ---- (a) unobserved read: no subscribe/leak, correct value after source writes ------------
    @Test
    fun unobservedDerivedDoesNotLeakAndStillReadsCorrectlyAfterSourceWrites() {
        val computeCount = AtomicInteger(0)
        val source = store(0)
        val derived = DerivedCell(EqualityPolicy.structural()) {
            computeCount.incrementAndGet()
            source.value * 2
        }

        // First on-demand read (no observer): recompute once, correct value, NO source listener.
        assertEquals(0, derived.value, "sanity: unobserved derived = source(0) * 2")
        val computesAfterFirstRead = computeCount.get()
        assertEquals(1, computesAfterFirstRead, "an unobserved read must compute exactly once")

        // Hammer the source. With no observer, none of these writes may reach the derived cell:
        // if the derived had leaked a subscription, each write would eagerly recompute it.
        repeat(5) { i -> source.value = i + 1 }
        assertEquals(
            computesAfterFirstRead,
            computeCount.get(),
            "unobserved derived must NOT subscribe to its source; " +
                "${computeCount.get() - computesAfterFirstRead} leaked eager recomputes occurred",
        )

        // Now read again on demand: the derived must self-heal from the snapshotted dependency
        // version and return the CORRECT up-to-date value (source last written = 5 -> 10), with
        // exactly one additional recompute (the on-demand heal), not one-per-write.
        assertEquals(10, derived.value, "unobserved re-read must return the current value 5 * 2 = 10")
        assertEquals(
            computesAfterFirstRead + 1,
            computeCount.get(),
            "the stale re-read must trigger exactly ONE on-demand recompute",
        )

        // A repeat read with no intervening write must be cached (no further recompute).
        assertEquals(10, derived.value)
        assertEquals(
            computesAfterFirstRead + 1,
            computeCount.get(),
            "a clean unobserved re-read must not recompute again",
        )
    }

    // ---- (b) throwing compute must not detach an OBSERVED derived -----------------------------
    @Test
    fun computeThrowingOnceKeepsObservedDerivedNotifyingOnLaterWrites() {
        val source = store(0)
        val computeCount = AtomicInteger(0)
        // compute() throws exactly once, at the sentinel source value 1.
        val derived = DerivedCell(EqualityPolicy.structural()) {
            computeCount.incrementAndGet()
            val v = source.value
            if (v == 1) throw IllegalStateException("boom on sentinel")
            v * 2
        }

        // Record every value the observer is notified with, so a missed/extra/glitch notify fails.
        val published = mutableListOf<Int>()
        val sub = derived.observe { published += derived.value }

        // observe() activates: initial compute succeeds and subscribes to source.
        assertEquals(0, derived.value, "sanity: initial derived = 0")
        assertEquals(emptyList(), published, "activation must not notify")

        // Drive the throwing recompute via a source write; the throw surfaces through the write.
        try {
            source.value = 1
            fail("expected the throwing recompute to propagate out of the source write")
        } catch (expected: IllegalStateException) {
            // expected
        }
        // The failed recompute must NOT have produced a notification.
        assertEquals(emptyList(), published, "a throwing recompute must not notify an observer")

        // The reactive link must have SURVIVED the throw: subsequent writes still notify, each
        // exactly once, with the correct healed values (2 -> 4, 3 -> 6).
        source.value = 2
        source.value = 3
        assertEquals(
            listOf(4, 6),
            published,
            "after a transient throwing recompute the OBSERVED derived must keep notifying " +
                "exactly once per changed write with correct values; saw $published",
        )
        assertEquals(6, derived.value, "final healed value must be 3 * 2 = 6")

        sub.dispose()
    }

    // ---- (c) a throwing listener must not suppress siblings in the SAME flush -----------------
    @Test
    fun throwingListenerDoesNotSuppressSiblingObserversInTheSameFlush() {
        // Two independent derived cells over the same source: one source write reaches BOTH in a
        // single wave, so their observers are notified in the SAME flush (one toNotify batch).
        val source = store(0)
        val a = DerivedCell(EqualityPolicy.structural()) { source.value + 1 }
        val b = DerivedCell(EqualityPolicy.structural()) { source.value + 2 }

        val aThrowSeen = mutableListOf<Int>() // sibling on cell A that THROWS after recording
        val aSiblingSeen = mutableListOf<Int>() // sibling on cell A (same cell, different flush entry)
        val bSeen = mutableListOf<Int>() // independent observer of cell B (different cell, same flush)

        // Register the throwing listener FIRST so, if exceptions were not isolated, it would abort
        // the rest of the same-flush notification batch.
        val aThrowSub = a.observe {
            aThrowSeen += a.value
            throw RuntimeException("boom from A's first listener")
        }
        val aSiblingSub = a.observe { aSiblingSeen += a.value }
        val bSub = b.observe { bSeen += b.value }

        // Activation reads.
        assertEquals(1, a.value)
        assertEquals(2, b.value)
        assertTrue(aThrowSeen.isEmpty() && aSiblingSeen.isEmpty() && bSeen.isEmpty())

        // One write feeds the whole wave: A -> 11, B -> 12. The throwing listener runs inside the
        // flush; notifyAll must still deliver both siblings before rethrowing.
        try {
            source.value = 10
            fail("expected the throwing listener to surface out of the write")
        } catch (expected: RuntimeException) {
            // expected — one throwing listener's error is rethrown after all fired.
        }

        assertEquals(
            listOf(11),
            aThrowSeen,
            "the throwing listener itself must fire exactly once with the final value 11; saw $aThrowSeen",
        )
        assertEquals(
            listOf(11),
            aSiblingSeen,
            "a sibling observer on the SAME cell must not be suppressed by a throwing sibling; " +
                "expected [11], saw $aSiblingSeen",
        )
        assertEquals(
            listOf(12),
            bSeen,
            "an independent observer of a different cell reached in the SAME flush must not be " +
                "suppressed by a throwing listener; expected [12], saw $bSeen",
        )

        // Final settled values are consistent (no glitch left behind).
        assertEquals(11, a.value)
        assertEquals(12, b.value)

        aThrowSub.dispose()
        aSiblingSub.dispose()
        bSub.dispose()
    }
}
