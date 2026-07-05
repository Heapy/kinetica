package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R05 (D-notification) — a shared intermediate derived with a SECOND independent observer.
 *
 * Topology:
 *
 *         S
 *        / \
 *       A   B ---- D = B * 100
 *        \ /
 *         C = A + B
 *
 * Both C and D are observed. C reads B (recomputing/self-healing it during C's own recompute).
 * D is an INDEPENDENT observer of that same shared B. On the old pull-based design, once B was
 * self-healed while C recomputed, B's own dependency callback short-circuited via
 * needsRecomputeLocked(), so D was NEVER notified — D's observer fired ZERO times on an S write
 * and D's value only corrected on a later manual read.
 *
 * The mark/flush wave collects observer-bearing cells STRUCTURALLY (unpruned reverse-graph walk),
 * so D is always collected; D.versionHealed self-heals D (post != pre) and D is notified exactly
 * once regardless of whether B was healed earlier during C's read.
 *
 * Asserts: both C and D are notified EXACTLY once, with the final consistent values, never a
 * glitch (mixed old/new) value, and each of A, B, C, D recomputes AT MOST once per write.
 */
class DerivedCellDiamondMultiObserverTest {
    @Test
    fun secondIndependentObserverOfSharedIntermediateIsNotifiedExactlyOnce() {
        val aCompute = AtomicInteger(0)
        val bCompute = AtomicInteger(0)
        val cCompute = AtomicInteger(0)
        val dCompute = AtomicInteger(0)

        val s = store(0)
        val a = DerivedCell(EqualityPolicy.structural()) { aCompute.incrementAndGet(); s.value + 1 }
        val b = DerivedCell(EqualityPolicy.structural()) { bCompute.incrementAndGet(); s.value + 10 }
        val c = DerivedCell(EqualityPolicy.structural()) { cCompute.incrementAndGet(); a.value + b.value }
        val d = DerivedCell(EqualityPolicy.structural()) { dCompute.incrementAndGet(); b.value * 100 }

        // Record every value each observer sees, so a transient glitch value would be caught.
        val cPublished = mutableListOf<Int>()
        val dPublished = mutableListOf<Int>()
        val cSub = c.observe { cPublished += c.value }
        val dSub = d.observe { dPublished += d.value }

        // Establish the initial consistent state, then measure ONLY the write's wave.
        assertEquals(11, c.value, "sanity: initial C = A(1) + B(10)")
        assertEquals(1000, d.value, "sanity: initial D = B(10) * 100")
        aCompute.set(0); bCompute.set(0); cCompute.set(0); dCompute.set(0)
        cPublished.clear(); dPublished.clear()

        // Single write to the shared source. New consistent values:
        //   A = 6, B = 15, C = A + B = 21, D = B * 100 = 1500.
        s.value = 5

        // Snapshot the per-cell recompute counts for the write's wave BEFORE any teardown: disposing
        // the last observer clears subscriptions and marks the cell dirty, so a later .value read
        // would force an extra (post-wave) recompute that is not part of the write's wave.
        val aWave = aCompute.get()
        val bWave = bCompute.get()
        val cWave = cCompute.get()
        val dWave = dCompute.get()

        // C: notified exactly once, with the final consistent value, never the A_new+B_old glitch (16).
        assertEquals(
            listOf(21),
            cPublished,
            "C must be notified exactly once with the final consistent value 21; saw $cPublished",
        )
        // D: the R05 D-notification fix — the SECOND independent observer of the shared B must fire
        // exactly once (it fired ZERO times on the buggy code), never publishing the old value 1000.
        assertEquals(
            listOf(1500),
            dPublished,
            "D (second observer of shared B) must be notified exactly once with 1500; saw $dPublished",
        )

        // Read while still observed (clean cells — no extra recompute) to confirm final values.
        assertEquals(21, c.value)
        assertEquals(1500, d.value)

        // Each diamond cell recomputes at most once per wave (version-dedup / topological batching).
        val counts = "A=$aWave B=$bWave C=$cWave D=$dWave"
        assertTrue(
            aWave <= 1 && bWave <= 1 && cWave <= 1 && dWave <= 1,
            "one write to shared source S must recompute each cell at most once per wave: $counts",
        )

        cSub.dispose()
        dSub.dispose()
    }
}
