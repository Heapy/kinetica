package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R05 — Diamond dependency: no topological batching, so shared cells recompute more than
 * once per propagation wave ("derived recomputes twice").
 *
 * Topology (a "diamond"):
 *
 *         S
 *        / \
 *       A   B
 *        \ /
 *         C = A + B
 *
 * A single write to the shared source S invalidates A, B and C. With correct topological
 * batching each derived cell in the wave recomputes AT MOST ONCE: S is marked, A/B/C are
 * marked dirty, then the wave is flushed in dependency order — A once, B once, C once.
 *
 * Today there is no batching. Writing S fires A's and B's source subscriptions independently
 * and, because C eagerly recomputes on each dependency callback (reading the other leg, which
 * lazily self-heals), the shared legs are recomputed repeatedly within the SAME wave:
 * for one S write, B's compute() runs 3 times and A's runs 2 times. This test asserts the
 * desired "at most once per wave" behavior, so it FAILS on the current code.
 *
 * Note: the classic *value* glitch (C transiently exposing A_new+B_old) and the double
 * notification of C are, on this codebase, already suppressed as a side effect of the
 * R03/R09/R11 fixes (version published before value + lazy per-dependency version check, so
 * the second leg self-heals when C reads it). Those consistency properties are asserted below
 * and currently HOLD; the surviving, still-failing R05 symptom is the redundant recomputation
 * caused by the absence of topological batching.
 */
class DerivedCellDiamondNoGlitchTest {
    @Test
    fun diamondBatchesEachDerivedToAtMostOneRecomputePerWave() {
        val aCompute = AtomicInteger(0)
        val bCompute = AtomicInteger(0)
        val cCompute = AtomicInteger(0)

        val s = store(1)
        val a = DerivedCell(EqualityPolicy.structural()) { aCompute.incrementAndGet(); s.value }
        val b = DerivedCell(EqualityPolicy.structural()) { bCompute.incrementAndGet(); s.value }
        val c = DerivedCell(EqualityPolicy.structural()) { cCompute.incrementAndGet(); a.value + b.value }

        // Record every value C publishes to its observer during the write.
        val published = mutableListOf<Int>()
        val disposable = c.observe { published += c.value }

        // Establish the initial consistent state, then measure ONLY the write's wave.
        assertEquals(2, c.value)
        aCompute.set(0)
        bCompute.set(0)
        cCompute.set(0)

        // Single write to the shared source. New consistent value: A(10) + B(10) = 20.
        s.value = 10
        disposable.dispose()

        // Consistency guards (currently HOLD): C never publishes the A_new+B_old glitch (11)
        // and is notified exactly once with the final value.
        assertTrue(
            11 !in published,
            "C must never publish the transient glitch value 11 (A_new + B_old); saw $published",
        )
        assertEquals(
            listOf(20),
            published,
            "C must be notified exactly once with the final consistent value 20; saw $published",
        )

        // Desired topological batching (currently FAILS): one write to the shared source must
        // recompute each diamond cell at most once. Without batching the shared legs recompute
        // repeatedly within the same wave.
        val counts = "A=${aCompute.get()} B=${bCompute.get()} C=${cCompute.get()}"
        assertTrue(
            aCompute.get() <= 1 && bCompute.get() <= 1 && cCompute.get() <= 1,
            "one write to shared source S must recompute each diamond cell at most once per " +
                "wave (topological batching), but a leg recomputed multiple times: $counts",
        )
    }
}
