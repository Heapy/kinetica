package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

        val mPublished = mutableListOf<Int>()
        val nPublished = mutableListOf<Int>()
        val topPublished = mutableListOf<Int>()
        val mSub = m.observe { mPublished += m.value }
        val nSub = n.observe { nPublished += n.value }
        val topSub = top.observe { topPublished += top.value }

        assertEquals(11, m.value, "sanity: initial M = A(1) + B(10)")
        assertEquals(1100, n.value, "sanity: initial N = X(100) + Y(1000)")
        assertEquals(1111, top.value, "sanity: initial top = M(11) + N(1100)")

        aCompute.set(0); bCompute.set(0); xCompute.set(0); yCompute.set(0)
        mCompute.set(0); nCompute.set(0); topCompute.set(0)
        mPublished.clear(); nPublished.clear(); topPublished.clear()

        s.value = 5

        val aWave = aCompute.get()
        val bWave = bCompute.get()
        val xWave = xCompute.get()
        val yWave = yCompute.get()
        val mWave = mCompute.get()
        val nWave = nCompute.get()
        val topWave = topCompute.get()

        assertEquals(
            listOf(21),
            mPublished,
            "M must be notified exactly once with the final consistent value 21; saw $mPublished",
        )
        assertEquals(
            listOf(1110),
            nPublished,
            "N must be notified exactly once with the final consistent value 1110; saw $nPublished",
        )
        assertEquals(
            listOf(1131),
            topPublished,
            "top must be notified exactly once with the final consistent value 1131; saw $topPublished",
        )

        assertTrue(16 !in mPublished, "M leaked a mixed old/new glitch (16); saw $mPublished")
        assertTrue(1105 !in nPublished, "N leaked a mixed old/new glitch (1105); saw $nPublished")
        assertTrue(
            1121 !in topPublished && 1111 !in topPublished,
            "top leaked a mixed/stale value; saw $topPublished",
        )

        assertEquals(21, m.value)
        assertEquals(1110, n.value)
        assertEquals(1131, top.value)

        val counts =
            "A=$aWave B=$bWave X=$xWave Y=$yWave M=$mWave N=$nWave top=$topWave"
        assertTrue(
            aWave <= 1 && bWave <= 1 && xWave <= 1 && yWave <= 1 &&
                mWave <= 1 && nWave <= 1 && topWave <= 1,
            "one write to shared source S must recompute each derived at most once per wave: $counts",
        )
        assertEquals(1, mWave, "M's value changed, so it must recompute exactly once: $counts")
        assertEquals(1, nWave, "N's value changed, so it must recompute exactly once: $counts")
        assertEquals(1, topWave, "top's value changed, so it must recompute exactly once: $counts")

        mSub.dispose()
        nSub.dispose()
        topSub.dispose()
    }
}
