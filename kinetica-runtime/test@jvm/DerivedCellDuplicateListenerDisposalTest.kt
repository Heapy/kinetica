package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

class DerivedCellDuplicateListenerDisposalTest {
    @Test
    fun disposingOneOfTwoIdenticalListenersKeepsTheOtherLive() {
        val source = store(1)
        val derived = DerivedCell(EqualityPolicy.structural()) {
            source.value * 2
        }

        val fires = AtomicInteger(0)
        val listener: () -> Unit = { fires.incrementAndGet() }

        val handle1 = derived.observe(listener)
        val handle2 = derived.observe(listener)

        require(derived.value == 2)

        handle1.dispose()

        source.value = 5

        assertTrue(
            fires.get() >= 1,
            "After disposing handle1, the still-live handle2 observer must still fire on " +
                "a source write, but it fired ${fires.get()} times — disposing one " +
                "identity-equal listener silenced the other and tore down subscriptions.",
        )

        val firesBeforeSecondWrite = fires.get()
        source.value = 9
        assertTrue(
            fires.get() > firesBeforeSecondWrite,
            "The derived cell must remain subscribed to its source after disposing one " +
                "of two identical listeners.",
        )

        handle2.dispose()
    }
}
