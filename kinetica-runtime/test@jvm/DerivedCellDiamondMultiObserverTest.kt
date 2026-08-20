package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

        val cPublished = mutableListOf<Int>()
        val dPublished = mutableListOf<Int>()
        val cSub = c.observe { cPublished += c.value }
        val dSub = d.observe { dPublished += d.value }

        assertEquals(11, c.value, "sanity: initial C = A(1) + B(10)")
        assertEquals(1000, d.value, "sanity: initial D = B(10) * 100")
        aCompute.set(0); bCompute.set(0); cCompute.set(0); dCompute.set(0)
        cPublished.clear(); dPublished.clear()

        s.value = 5

        val aWave = aCompute.get()
        val bWave = bCompute.get()
        val cWave = cCompute.get()
        val dWave = dCompute.get()

        assertEquals(
            listOf(21),
            cPublished,
            "C must be notified exactly once with the final consistent value 21; saw $cPublished",
        )
        assertEquals(
            listOf(1500),
            dPublished,
            "D (second observer of shared B) must be notified exactly once with 1500; saw $dPublished",
        )

        assertEquals(21, c.value)
        assertEquals(1500, d.value)

        val counts = "A=$aWave B=$bWave C=$cWave D=$dWave"
        assertTrue(
            aWave <= 1 && bWave <= 1 && cWave <= 1 && dWave <= 1,
            "one write to shared source S must recompute each cell at most once per wave: $counts",
        )

        cSub.dispose()
        dSub.dispose()
    }
}
