package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

        val published = mutableListOf<Int>()
        val disposable = c.observe { published += c.value }

        assertEquals(2, c.value)
        aCompute.set(0)
        bCompute.set(0)
        cCompute.set(0)

        s.value = 10
        disposable.dispose()

        assertTrue(
            11 !in published,
            "C must never publish the transient glitch value 11 (A_new + B_old); saw $published",
        )
        assertEquals(
            listOf(20),
            published,
            "C must be notified exactly once with the final consistent value 20; saw $published",
        )

        val counts = "A=${aCompute.get()} B=${bCompute.get()} C=${cCompute.get()}"
        assertTrue(
            aCompute.get() <= 1 && bCompute.get() <= 1 && cCompute.get() <= 1,
            "one write to shared source S must recompute each diamond cell at most once per " +
                "wave (topological batching), but a leg recomputed multiple times: $counts",
        )
    }
}
