package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R11 — TOCTOU: a DerivedCell subscribes to a dependency only AFTER compute() has already
 * read it. A write to that dependency landing in the window between the read and the
 * `observe()` subscribe notifies a listener snapshot that does not yet include this cell,
 * so the update is lost and the derived stays permanently stale.
 *
 * recomputeLocked() (Cell.kt:244-303) runs compute() FIRST, collecting the dependency set,
 * and only afterwards attaches source subscriptions (Cell.kt:266-282) and records the
 * post-subscribe dependency versions (Cell.kt:286-289). A source write that happens after
 * compute() read the value but before the subscription exists:
 *   1. is never delivered to this cell (it is not yet subscribed), AND
 *   2. is masked from the lazy version check, because dependencyVersions is captured AFTER
 *      the write, so the recorded version already equals the new source version.
 * The cell therefore caches the value computed from the OLD source read and never recomputes.
 *
 * This test reproduces that window deterministically (single-threaded) via a test hook: the
 * compute() writes the source exactly once, on its first pass, after it has read the source.
 * That models a concurrent writer committing inside the read-to-subscribe window.
 *
 * Desired/correct behavior (asserted here, currently FAILS): after the source has been
 * advanced to 5, the derived must reflect the NEW source value (5 * 2 == 10), not the stale
 * value computed from the pre-write read (1 * 2 == 2).
 */
class DerivedCellRecomputeLostUpdateTest {
    @Test
    fun writeInReadToSubscribeWindowIsNotLost() {
        val source = store(1)

        // On the first compute pass we read the source, then mutate it once — simulating a
        // write that commits after compute()'s read but before the cell has subscribed.
        var injectedWrite = false
        val derived = DerivedCell(EqualityPolicy.structural()) {
            val observed = source.value          // read the dependency (TOC)
            if (!injectedWrite) {
                injectedWrite = true
                source.value = 5                 // write lands in the read-to-subscribe window
            }
            observed * 2
        }

        // Activating the cell triggers the recompute + subscribe sequence in which the
        // injected write occurs. The derived subscribes to `source` only after compute()
        // already read 1 and after `source` has already advanced to 5.
        derived.observe {}

        // Sanity: the injected write actually happened and the source is now 5.
        assertEquals(5, source.value, "test hook must have advanced the source to 5")

        assertEquals(
            10,
            derived.value,
            "a source write landing between compute()'s read and the subscribe must not be " +
                "lost: derived should reflect the new source value (5 * 2), but it stayed stale " +
                "at the value computed from the pre-write read (1 * 2)",
        )
    }
}
