package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * R08 — DerivedCell.observe() establishes no subscriptions; observe-before-first-read is inert.
 *
 * Desired behavior: the first observer of a DerivedCell activates it (compute + subscribe to
 * current dependencies), so a source write reaches the observer even if the cell's .value was
 * never read. Today observe() only appends a listener; with no prior read the cell has zero
 * source subscriptions, so the source write notifies nobody and this test FAILS.
 */
class DerivedCellObserveBeforeReadActivatesTest {
    @Test
    fun observeBeforeAnyReadStillDeliversSourceChanges() {
        val source = store(1)
        val derived = DerivedCell(EqualityPolicy.structural()) {
            source.value * 2
        }

        var fired = false
        // Observe WITHOUT ever reading derived.value first.
        derived.observe { fired = true }

        // Write the source dependency.
        source.value = 5

        assertTrue(
            fired,
            "observe() before any read must activate the derived cell and deliver source changes, " +
                "but the observer was never notified",
        )
    }
}
