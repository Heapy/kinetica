package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertTrue

class DerivedCellObserveBeforeReadActivatesTest {
    @Test
    fun observeBeforeAnyReadStillDeliversSourceChanges() {
        val source = store(1)
        val derived = DerivedCell(EqualityPolicy.structural()) {
            source.value * 2
        }

        var fired = false
        derived.observe { fired = true }

        source.value = 5

        assertTrue(
            fired,
            "observe() before any read must activate the derived cell and deliver source changes, " +
                "but the observer was never notified",
        )
    }
}
