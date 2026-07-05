package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R05 (mark/flush scheduler) — DEEP linear chain, adversarial.
 *
 * Topology (a straight chain, no diamond — every derived reads ONLY the previous one):
 *
 *     S --> A=S+1 --> B=A+1 --> C=B+1 --> D=C+1
 *
 * A single write to S must ripple to the terminal D through four intermediate self-heals.
 * This stresses the scheduler differently from the diamond: the failure modes it hunts for are
 *
 *  1. RE-ENTRANT OVER-RECOMPUTE — a naive push design would fire A's callback, which recomputes
 *     B, whose callback recomputes C, whose callback recomputes D, and if each level also
 *     re-pulls upstream, an O(n^2) storm makes some cell recompute more than once. We assert
 *     each of A, B, C, D recomputes AT MOST ONCE for the single S write.
 *  2. GLITCH AT AN INTERMEDIATE OBSERVER — an observer attached at C must NEVER be handed a value
 *     computed from a half-propagated chain (e.g. C=8 while D still says 4, or any value of C
 *     other than the final 8). We record EVERY value C's observer sees; anything but exactly
 *     [8] fails.
 *  3. MISSED / DUPLICATE TERMINAL NOTIFICATION — the terminal observer at D must fire EXACTLY
 *     once and read the fully-updated value 9 (never the stale 4, never a mid-ripple value,
 *     never twice). We record every value D's observer sees; anything but exactly [9] fails.
 *  4. STALE READ INSIDE A LISTENER — when C's or D's listener runs, reading up the whole chain
 *     (S,A,B,C,D) must return the single final consistent tuple (5,6,7,8,9), proving NOTIFY runs
 *     only after every cell settled.
 *
 * Deterministic and single-threaded: the propagation wave runs entirely on the writing thread's
 * stack, so no sleeps or latches are needed to observe its full effect.
 */
class SchedulerAdversarial_deepchainTest {
    @Test
    fun deepChainRecomputesAtMostOnceTerminalNotifiedOnceNoIntermediateGlitch() {
        val aCompute = AtomicInteger(0)
        val bCompute = AtomicInteger(0)
        val cCompute = AtomicInteger(0)
        val dCompute = AtomicInteger(0)

        val s = store(0)
        val a = DerivedCell(EqualityPolicy.structural()) { aCompute.incrementAndGet(); s.value + 1 }
        val b = DerivedCell(EqualityPolicy.structural()) { bCompute.incrementAndGet(); a.value + 1 }
        val c = DerivedCell(EqualityPolicy.structural()) { cCompute.incrementAndGet(); b.value + 1 }
        val d = DerivedCell(EqualityPolicy.structural()) { dCompute.incrementAndGet(); c.value + 1 }

        // Every value each observer is handed, in order — so a glitch, a miss, or a duplicate shows up.
        val cPublished = mutableListOf<Int>()
        val dPublished = mutableListOf<Int>()
        // The full-chain tuple observed at the instant each listener fires — proves NOTIFY is post-settle.
        val cChainSnapshots = mutableListOf<List<Int>>()
        val dChainSnapshots = mutableListOf<List<Int>>()

        // Observer attached at the INTERMEDIATE C, plus the TERMINAL observer at D.
        val cSub = c.observe {
            cPublished += c.value
            cChainSnapshots += listOf(s.value, a.value, b.value, c.value, d.value)
        }
        val dSub = d.observe {
            dPublished += d.value
            dChainSnapshots += listOf(s.value, a.value, b.value, c.value, d.value)
        }

        // Establish the initial consistent state (S=0 => A=1,B=2,C=3,D=4), then measure ONLY the write.
        assertEquals(4, d.value, "sanity: initial D = S(0)+1+1+1+1")
        assertEquals(3, c.value, "sanity: initial C = S(0)+1+1+1")
        aCompute.set(0); bCompute.set(0); cCompute.set(0); dCompute.set(0)
        cPublished.clear(); dPublished.clear()
        cChainSnapshots.clear(); dChainSnapshots.clear()

        // The single write under test. New consistent chain: A=6, B=7, C=8, D=9.
        s.value = 5

        // Snapshot recompute counts for the wave BEFORE any teardown — disposing the last observer
        // clears subscriptions and dirties the cell, so a later read would force an extra recompute
        // that does not belong to the write's wave.
        val aWave = aCompute.get()
        val bWave = bCompute.get()
        val cWave = cCompute.get()
        val dWave = dCompute.get()

        // (2) Intermediate observer at C sees exactly the final value once — never a glitch, never twice.
        assertEquals(
            listOf(8),
            cPublished,
            "C (intermediate observer) must be notified exactly once with the final value 8; saw $cPublished",
        )
        // (3) Terminal observer at D fires exactly once with the fully-updated value — never missed,
        //     never the stale 4, never a mid-ripple value, never duplicated.
        assertEquals(
            listOf(9),
            dPublished,
            "D (terminal observer) must be notified exactly once with the fully-updated value 9; saw $dPublished",
        )

        // (4) Whenever a listener ran, the WHOLE chain was already at its final consistent tuple.
        assertEquals(
            listOf(listOf(5, 6, 7, 8, 9)),
            cChainSnapshots,
            "at C's notification the whole chain must be fully settled; saw $cChainSnapshots",
        )
        assertEquals(
            listOf(listOf(5, 6, 7, 8, 9)),
            dChainSnapshots,
            "at D's notification the whole chain must be fully settled; saw $dChainSnapshots",
        )

        // Read while still observed (clean cells — no extra recompute) to confirm the final values.
        assertEquals(6, a.value)
        assertEquals(7, b.value)
        assertEquals(8, c.value)
        assertEquals(9, d.value)

        // (1) One write to S recomputes each cell in the chain AT MOST once (no re-entrant storm).
        val counts = "A=$aWave B=$bWave C=$cWave D=$dWave"
        assertTrue(
            aWave <= 1 && bWave <= 1 && cWave <= 1 && dWave <= 1,
            "one write to source S must recompute each chain cell at most once per wave: $counts",
        )
        // And every cell whose value changed must have actually recomputed once (no missed heal).
        assertEquals(1, aWave, "A must recompute exactly once: $counts")
        assertEquals(1, bWave, "B must recompute exactly once: $counts")
        assertEquals(1, cWave, "C must recompute exactly once: $counts")
        assertEquals(1, dWave, "D must recompute exactly once: $counts")

        cSub.dispose()
        dSub.dispose()
    }
}
