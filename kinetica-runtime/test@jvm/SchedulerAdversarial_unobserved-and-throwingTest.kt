package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class `SchedulerAdversarial_unobserved-and-throwingTest` {

    @Test
    fun unobservedDerivedDoesNotLeakAndStillReadsCorrectlyAfterSourceWrites() {
        val computeCount = AtomicInteger(0)
        val source = store(0)
        val derived = DerivedCell(EqualityPolicy.structural()) {
            computeCount.incrementAndGet()
            source.value * 2
        }

        assertEquals(0, derived.value, "sanity: unobserved derived = source(0) * 2")
        val computesAfterFirstRead = computeCount.get()
        assertEquals(1, computesAfterFirstRead, "an unobserved read must compute exactly once")

        repeat(5) { i -> source.value = i + 1 }
        assertEquals(
            computesAfterFirstRead,
            computeCount.get(),
            "unobserved derived must NOT subscribe to its source; " +
                "${computeCount.get() - computesAfterFirstRead} leaked eager recomputes occurred",
        )

        assertEquals(10, derived.value, "unobserved re-read must return the current value 5 * 2 = 10")
        assertEquals(
            computesAfterFirstRead + 1,
            computeCount.get(),
            "the stale re-read must trigger exactly ONE on-demand recompute",
        )

        assertEquals(10, derived.value)
        assertEquals(
            computesAfterFirstRead + 1,
            computeCount.get(),
            "a clean unobserved re-read must not recompute again",
        )
    }

    @Test
    fun computeThrowingOnceKeepsObservedDerivedNotifyingOnLaterWrites() {
        val source = store(0)
        val computeCount = AtomicInteger(0)
        val derived = DerivedCell(EqualityPolicy.structural()) {
            computeCount.incrementAndGet()
            val v = source.value
            if (v == 1) throw IllegalStateException("boom on sentinel")
            v * 2
        }

        val published = mutableListOf<Int>()
        val sub = derived.observe { published += derived.value }

        assertEquals(0, derived.value, "sanity: initial derived = 0")
        assertEquals(emptyList(), published, "activation must not notify")

        try {
            source.value = 1
            fail("expected the throwing recompute to propagate out of the source write")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(emptyList(), published, "a throwing recompute must not notify an observer")

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

    @Test
    fun throwingListenerDoesNotSuppressSiblingObserversInTheSameFlush() {
        val source = store(0)
        val a = DerivedCell(EqualityPolicy.structural()) { source.value + 1 }
        val b = DerivedCell(EqualityPolicy.structural()) { source.value + 2 }

        val aThrowSeen = mutableListOf<Int>() // sibling on cell A that THROWS after recording
        val aSiblingSeen = mutableListOf<Int>() // sibling on cell A (same cell, different flush entry)
        val bSeen = mutableListOf<Int>() // independent observer of cell B (different cell, same flush)

        val aThrowSub = a.observe {
            aThrowSeen += a.value
            throw RuntimeException("boom from A's first listener")
        }
        val aSiblingSub = a.observe { aSiblingSeen += a.value }
        val bSub = b.observe { bSeen += b.value }

        assertEquals(1, a.value)
        assertEquals(2, b.value)
        assertTrue(aThrowSeen.isEmpty() && aSiblingSeen.isEmpty() && bSeen.isEmpty())

        try {
            source.value = 10
            fail("expected the throwing listener to surface out of the write")
        } catch (expected: RuntimeException) {
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

        assertEquals(11, a.value)
        assertEquals(12, b.value)

        aThrowSub.dispose()
        aSiblingSub.dispose()
        bSub.dispose()
    }
}
