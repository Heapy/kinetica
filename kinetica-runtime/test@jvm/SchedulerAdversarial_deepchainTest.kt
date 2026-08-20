package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

        val cPublished = mutableListOf<Int>()
        val dPublished = mutableListOf<Int>()
        val cChainSnapshots = mutableListOf<List<Int>>()
        val dChainSnapshots = mutableListOf<List<Int>>()

        val cSub = c.observe {
            cPublished += c.value
            cChainSnapshots += listOf(s.value, a.value, b.value, c.value, d.value)
        }
        val dSub = d.observe {
            dPublished += d.value
            dChainSnapshots += listOf(s.value, a.value, b.value, c.value, d.value)
        }

        assertEquals(4, d.value, "sanity: initial D = S(0)+1+1+1+1")
        assertEquals(3, c.value, "sanity: initial C = S(0)+1+1+1")
        aCompute.set(0); bCompute.set(0); cCompute.set(0); dCompute.set(0)
        cPublished.clear(); dPublished.clear()
        cChainSnapshots.clear(); dChainSnapshots.clear()

        s.value = 5

        val aWave = aCompute.get()
        val bWave = bCompute.get()
        val cWave = cCompute.get()
        val dWave = dCompute.get()

        assertEquals(
            listOf(8),
            cPublished,
            "C (intermediate observer) must be notified exactly once with the final value 8; saw $cPublished",
        )
        assertEquals(
            listOf(9),
            dPublished,
            "D (terminal observer) must be notified exactly once with the fully-updated value 9; saw $dPublished",
        )

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

        assertEquals(6, a.value)
        assertEquals(7, b.value)
        assertEquals(8, c.value)
        assertEquals(9, d.value)

        val counts = "A=$aWave B=$bWave C=$cWave D=$dWave"
        assertTrue(
            aWave <= 1 && bWave <= 1 && cWave <= 1 && dWave <= 1,
            "one write to source S must recompute each chain cell at most once per wave: $counts",
        )
        assertEquals(1, aWave, "A must recompute exactly once: $counts")
        assertEquals(1, bWave, "B must recompute exactly once: $counts")
        assertEquals(1, cWave, "C must recompute exactly once: $counts")
        assertEquals(1, dWave, "D must recompute exactly once: $counts")

        cSub.dispose()
        dSub.dispose()
    }
}
