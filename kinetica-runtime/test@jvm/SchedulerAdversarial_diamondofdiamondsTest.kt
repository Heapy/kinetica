package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R05 mark/flush scheduler — ADVERSARIAL "diamond of diamonds".
 *
 * Two independent diamonds share a single source S, and their apexes feed a top derived. On top of
 * that, the two intermediate apexes M and N are ALSO observed independently — so the wave must
 * settle M, N and top consistently AND fire three separate observers, each exactly once, from a
 * single S write.
 *
 * Topology:
 *
 *                 S
 *              /  |  |  \
 *             A   B  X   Y
 *              \ /    \ /
 *          M = A+B  N = X+Y
 *              \      /
 *            top = M + N
 *
 * observers: top (apex), and INDEPENDENTLY M and N (the shared intermediates that top itself reads
 * during its own recompute).
 *
 * This is the exact shape that broke the old pull-based design: while `top` recomputes it reads M
 * and N, self-healing them; on the buggy code the intermediates' own dependency callbacks then
 * short-circuit and their independent observers (the M and N observers here) are never fired. It
 * also stresses topological batching twice over — M's legs (A,B) and N's legs (X,Y) each share S,
 * so a non-batched design recomputes the legs and the apexes repeatedly within one wave.
 *
 * Correct mark/flush behavior asserted here (a glitch or a missed/extra notification FAILS):
 *  - every observer (M, N, top) is notified EXACTLY once on the single S write;
 *  - each observer only ever sees the final, consistent value — never a mixed old/new glitch;
 *  - each derived (A, B, X, Y, M, N, top) recomputes AT MOST once per wave.
 */
class SchedulerAdversarial_diamondofdiamondsTest {
    @Test
    fun nestedDiamondsSingleWriteNotifiesEveryObserverExactlyOnceGlitchFree() {
        val aCompute = AtomicInteger(0)
        val bCompute = AtomicInteger(0)
        val xCompute = AtomicInteger(0)
        val yCompute = AtomicInteger(0)
        val mCompute = AtomicInteger(0)
        val nCompute = AtomicInteger(0)
        val topCompute = AtomicInteger(0)

        val s = store(0)
        val a = DerivedCell(EqualityPolicy.structural()) { aCompute.incrementAndGet(); s.value + 1 }
        val b = DerivedCell(EqualityPolicy.structural()) { bCompute.incrementAndGet(); s.value + 10 }
        val x = DerivedCell(EqualityPolicy.structural()) { xCompute.incrementAndGet(); s.value + 100 }
        val y = DerivedCell(EqualityPolicy.structural()) { yCompute.incrementAndGet(); s.value + 1000 }
        val m = DerivedCell(EqualityPolicy.structural()) { mCompute.incrementAndGet(); a.value + b.value }
        val n = DerivedCell(EqualityPolicy.structural()) { nCompute.incrementAndGet(); x.value + y.value }
        val top = DerivedCell(EqualityPolicy.structural()) { topCompute.incrementAndGet(); m.value + n.value }

        // Record EVERY value each observer sees. Any glitch (mixed old/new) or missed/extra
        // notification changes one of these lists and fails the exact-match assertions below.
        val mPublished = mutableListOf<Int>()
        val nPublished = mutableListOf<Int>()
        val topPublished = mutableListOf<Int>()
        val mSub = m.observe { mPublished += m.value }
        val nSub = n.observe { nPublished += n.value }
        val topSub = top.observe { topPublished += top.value }

        // Establish the initial consistent state (S = 0):
        //   A=1, B=10, M=11 ; X=100, Y=1000, N=1100 ; top = M+N = 1111.
        assertEquals(11, m.value, "sanity: initial M = A(1) + B(10)")
        assertEquals(1100, n.value, "sanity: initial N = X(100) + Y(1000)")
        assertEquals(1111, top.value, "sanity: initial top = M(11) + N(1100)")

        // Reset all measurement AFTER activation, so we observe ONLY the write's wave.
        aCompute.set(0); bCompute.set(0); xCompute.set(0); yCompute.set(0)
        mCompute.set(0); nCompute.set(0); topCompute.set(0)
        mPublished.clear(); nPublished.clear(); topPublished.clear()

        // ---- The single write to the shared source. New consistent values (S = 5): ----
        //   A=6, B=15, M=21 ; X=105, Y=1005, N=1110 ; top = 21 + 1110 = 1131.
        s.value = 5

        // Snapshot per-cell recompute counts for the wave BEFORE any teardown: disposing the last
        // observer clears subscriptions + marks the cell dirty, so a post-wave .value read would
        // force an extra recompute that is not part of the write's wave.
        val aWave = aCompute.get()
        val bWave = bCompute.get()
        val xWave = xCompute.get()
        val yWave = yCompute.get()
        val mWave = mCompute.get()
        val nWave = nCompute.get()
        val topWave = topCompute.get()

        // ---- Exactly-once, glitch-free notification for every observer. ----
        // M: never the A_new+B_old / A_old+B_new glitch (16); fires once with the final 21.
        assertEquals(
            listOf(21),
            mPublished,
            "M must be notified exactly once with the final consistent value 21; saw $mPublished",
        )
        // N: never the X_new+Y_old / X_old+Y_new glitch (1105); fires once with the final 1110.
        assertEquals(
            listOf(1110),
            nPublished,
            "N must be notified exactly once with the final consistent value 1110; saw $nPublished",
        )
        // top: never any mixed value (e.g. old M(11)+new N(1110)=1121, or new M(21)+old N(1100)=1121,
        // or the wholly-stale 1111). Fires once with the final 1131.
        assertEquals(
            listOf(1131),
            topPublished,
            "top must be notified exactly once with the final consistent value 1131; saw $topPublished",
        )

        // Belt-and-suspenders: none of the enumerated glitch values were EVER published.
        assertTrue(16 !in mPublished, "M leaked a mixed old/new glitch (16); saw $mPublished")
        assertTrue(1105 !in nPublished, "N leaked a mixed old/new glitch (1105); saw $nPublished")
        assertTrue(
            1121 !in topPublished && 1111 !in topPublished,
            "top leaked a mixed/stale value; saw $topPublished",
        )

        // Read while STILL observed (clean cells -> no extra recompute) to confirm final values.
        assertEquals(21, m.value)
        assertEquals(1110, n.value)
        assertEquals(1131, top.value)

        // ---- At most one recompute per cell per wave (topological batching, both diamonds). ----
        val counts =
            "A=$aWave B=$bWave X=$xWave Y=$yWave M=$mWave N=$nWave top=$topWave"
        assertTrue(
            aWave <= 1 && bWave <= 1 && xWave <= 1 && yWave <= 1 &&
                mWave <= 1 && nWave <= 1 && topWave <= 1,
            "one write to shared source S must recompute each derived at most once per wave: $counts",
        )
        // And each observed apex/intermediate whose value actually changed DID recompute (not stale).
        assertEquals(1, mWave, "M's value changed, so it must recompute exactly once: $counts")
        assertEquals(1, nWave, "N's value changed, so it must recompute exactly once: $counts")
        assertEquals(1, topWave, "top's value changed, so it must recompute exactly once: $counts")

        mSub.dispose()
        nSub.dispose()
        topSub.dispose()
    }
}
