package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R14 — `DerivedCell.version` getter does not recompute a dirty cell, so `version` and
 * `value` can disagree.
 *
 * `value` recomputes when the cell is dirty (Cell.kt:196-207), bumping `versionCounter`.
 * `version` (Cell.kt:193-194) returns the raw counter without recomputing. After a source
 * change leaves the derived cell dirty, reading `version` reports a stale counter, while a
 * subsequent read of `value` recomputes and advances it. `SkippableNodeCache.dependenciesUnchanged()`
 * compares these versions and can therefore skip a needed re-render.
 *
 * Desired/correct behavior (asserted here, currently FAILS): the version observed while the
 * cell is dirty must equal the version observed after `value` has forced the recompute —
 * i.e. `version` must be self-consistent with `value`.
 */
class DerivedCellVersionMatchesValueWhenDirtyTest {
    @Test
    fun versionGetterRecomputesDirtyDerivedCellSoItAgreesWithValue() {
        val source = store(10)
        val derived = DerivedCell(EqualityPolicy.structural()) {
            source.value * 2
        }

        // Initialize the derived cell and establish its dependency subscription.
        val subscription = derived.observe {}
        assertEquals(20, derived.value)

        // Disposing the last observer clears the source subscription and marks the cell
        // dirty. A later source write is therefore NOT auto-recomputed; it must be picked
        // up on demand the next time the cell is queried.
        subscription.dispose()
        source.value = 11

        // Query version while the cell is dirty. Correct behavior: this reflects the
        // pending change, exactly as reading value would.
        val versionWhileDirty = derived.version

        // Force the canonical recompute via value; this is what actually advances the
        // version counter today.
        assertEquals(22, derived.value)
        val versionAfterValue = derived.version

        assertEquals(
            versionAfterValue,
            versionWhileDirty,
            "version getter must recompute a dirty DerivedCell so version agrees with value; " +
                "a stale version lets dependenciesUnchanged() skip a needed re-render",
        )
    }
}
