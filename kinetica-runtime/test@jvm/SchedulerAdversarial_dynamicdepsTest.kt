package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R05 mark/flush scheduler — ADVERSARIAL: conditional / dynamic dependencies.
 *
 * A derived cell whose dependency SET changes at runtime is the classic way to break a reactive
 * scheduler: after a branch flips, the cell must (a) start reacting to the now-relevant source,
 * (b) STOP reacting to the now-irrelevant source, and (c) leave no dangling reverse-graph edge on
 * the dropped source (no leak). A naive scheduler that never tears down a stale subscription will
 * either over-notify (fire the observer on a write to the irrelevant source) or silently retain the
 * edge (leak) — or, subtler, keep BFS-reaching the derived on every irrelevant write and needlessly
 * recompute it.
 *
 * Topology (a and b are independent sources; flag selects which one G tracks):
 *
 *      flag ─┐
 *        a ──┤
 *        b ──┴──► G = derived { if (flag.value) a.value else b.value }
 *
 * The observer records EVERY value it is handed, and every source write first zeroes G's recompute
 * counter, so a missed notification, an extra notification, a glitch (mixed old/new) value, OR a
 * needless recompute of the pruned branch all fail the test.
 *
 * The exact-once / glitch-free contract exercised here:
 *   - while flag == true  : writing b (irrelevant) must NOT notify G and must NOT even recompute G
 *                           (G is not subscribed to b); writing a (relevant) notifies G EXACTLY once.
 *   - toggling flag        : re-selects the branch; G recomputes once, is notified once with the new
 *                           consistent value, drops its edge on the old source, and gains one on the
 *                           new source.
 *   - while flag == false : writing a (now irrelevant, the FORMER dependency) must NOT notify G and
 *                           must NOT recompute G — this is the stale-dependency-drop proof; writing
 *                           b (now relevant) notifies G EXACTLY once.
 *   - disposal            : no source is left holding G as a dependent (no leak).
 */
class SchedulerAdversarial_dynamicdepsTest {
    // Reverse-graph edges of a source cell: the DerivedCells currently subscribed to it. Used to
    // prove that a stale dependency edge is actually TORN DOWN (not merely pruned at notify time).
    private fun dependentsOf(cell: Cell<*>): List<DerivedCell<*>> =
        (cell as ReactiveNode).snapshotDependents()

    private fun isDependent(source: Cell<*>, derived: DerivedCell<*>): Boolean =
        dependentsOf(source).any { it === derived }

    @Test
    fun conditionalDependencyDropsStaleSourceAndNotifiesExactlyOnceGlitchFree() {
        val flag = store(true)
        val a = store(1)
        val b = store(100)

        val gCompute = AtomicInteger(0)
        val g = DerivedCell(EqualityPolicy.structural()) {
            gCompute.incrementAndGet()
            if (flag.value) a.value else b.value
        }

        // Record EVERY value the observer is handed; a glitch or missed/extra notification shows up
        // as a wrong or missing/duplicated entry in this list.
        val gPublished = mutableListOf<Int>()
        val gSub = g.observe { gPublished += g.value }

        // Activate + settle the initial consistent state, then measure only the writes below.
        assertEquals(1, g.value, "sanity: flag=true selects a=1")
        assertTrue(isDependent(flag, g), "G must subscribe to flag")
        assertTrue(isDependent(a, g), "flag=true: G must subscribe to a (the selected source)")
        assertFalse(isDependent(b, g), "flag=true: G must NOT subscribe to b (the pruned branch)")
        gCompute.set(0)
        gPublished.clear()

        // ---- Phase 1: flag == true (G tracks a) ----------------------------------------------

        // Write the IRRELEVANT source b. G is not subscribed to b, so it must be neither reached
        // (0 recomputes) nor notified.
        gCompute.set(0)
        b.value = 200
        assertEquals(
            0,
            gCompute.get(),
            "flag=true: a write to the irrelevant source b must not even recompute G",
        )
        assertEquals(
            emptyList(),
            gPublished,
            "flag=true: a write to the irrelevant source b must NOT notify G; saw $gPublished",
        )

        // Write the RELEVANT source a. G must recompute once and be notified exactly once with 2.
        gCompute.set(0)
        a.value = 2
        assertEquals(1, gCompute.get(), "flag=true: relevant write to a must recompute G exactly once")
        assertEquals(
            listOf(2),
            gPublished,
            "flag=true: relevant write to a must notify G exactly once with 2; saw $gPublished",
        )

        // ---- Phase 2: toggle flag true -> false (re-select the branch) -----------------------

        // b was written to 200 in phase 1, so switching the branch must publish 200 (a consistent,
        // non-glitch value — never a's stale 2 nor b's pre-write 100).
        gCompute.set(0)
        flag.value = false
        assertEquals(1, gCompute.get(), "toggling flag must recompute G exactly once")
        assertEquals(
            listOf(2, 200),
            gPublished,
            "toggling flag must notify G exactly once with the newly selected b=200; saw $gPublished",
        )

        // The dependency SET must have flipped: the edge on the old source a is dropped, a new edge
        // on b is established, and flag is still tracked. This is the no-leak / stale-drop core.
        assertFalse(
            isDependent(a, g),
            "after toggle: G's stale edge on a must be TORN DOWN (leak/stale-dep bug otherwise)",
        )
        assertTrue(isDependent(b, g), "after toggle: G must now subscribe to the selected source b")
        assertTrue(isDependent(flag, g), "after toggle: G must still subscribe to flag")

        // ---- Phase 3: flag == false (G tracks b) ---------------------------------------------

        // Write the now-IRRELEVANT former source a. If the stale edge survived, this would reach and
        // recompute G; a correct scheduler neither recomputes nor notifies.
        gCompute.set(0)
        a.value = 3
        assertEquals(
            0,
            gCompute.get(),
            "flag=false: a write to the dropped source a must not even recompute G (stale-dep leak)",
        )
        assertEquals(
            listOf(2, 200),
            gPublished,
            "flag=false: a write to the dropped source a must NOT notify G; saw $gPublished",
        )

        // Write the RELEVANT source b. G recomputes once and is notified exactly once with 300.
        gCompute.set(0)
        b.value = 300
        assertEquals(1, gCompute.get(), "flag=false: relevant write to b must recompute G exactly once")
        assertEquals(
            listOf(2, 200, 300),
            gPublished,
            "flag=false: relevant write to b must notify G exactly once with 300; saw $gPublished",
        )

        // Read while still observed (clean cell) to confirm the settled value.
        assertEquals(300, g.value, "G's settled value must be the selected b=300")

        // ---- Disposal: no source may still hold G as a dependent (no leak) -------------------
        gSub.dispose()
        assertFalse(isDependent(flag, g), "after dispose: flag must not retain G (leak)")
        assertFalse(isDependent(a, g), "after dispose: a must not retain G (leak)")
        assertFalse(isDependent(b, g), "after dispose: b must not retain G (leak)")
    }
}
