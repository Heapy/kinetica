package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchedulerAdversarial_dynamicdepsTest {
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

        val gPublished = mutableListOf<Int>()
        val gSub = g.observe { gPublished += g.value }

        assertEquals(1, g.value, "sanity: flag=true selects a=1")
        assertTrue(isDependent(flag, g), "G must subscribe to flag")
        assertTrue(isDependent(a, g), "flag=true: G must subscribe to a (the selected source)")
        assertFalse(isDependent(b, g), "flag=true: G must NOT subscribe to b (the pruned branch)")
        gCompute.set(0)
        gPublished.clear()

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

        gCompute.set(0)
        a.value = 2
        assertEquals(1, gCompute.get(), "flag=true: relevant write to a must recompute G exactly once")
        assertEquals(
            listOf(2),
            gPublished,
            "flag=true: relevant write to a must notify G exactly once with 2; saw $gPublished",
        )

        gCompute.set(0)
        flag.value = false
        assertEquals(1, gCompute.get(), "toggling flag must recompute G exactly once")
        assertEquals(
            listOf(2, 200),
            gPublished,
            "toggling flag must notify G exactly once with the newly selected b=200; saw $gPublished",
        )

        assertFalse(
            isDependent(a, g),
            "after toggle: G's stale edge on a must be TORN DOWN (leak/stale-dep bug otherwise)",
        )
        assertTrue(isDependent(b, g), "after toggle: G must now subscribe to the selected source b")
        assertTrue(isDependent(flag, g), "after toggle: G must still subscribe to flag")

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

        gCompute.set(0)
        b.value = 300
        assertEquals(1, gCompute.get(), "flag=false: relevant write to b must recompute G exactly once")
        assertEquals(
            listOf(2, 200, 300),
            gPublished,
            "flag=false: relevant write to b must notify G exactly once with 300; saw $gPublished",
        )

        assertEquals(300, g.value, "G's settled value must be the selected b=300")

        gSub.dispose()
        assertFalse(isDependent(flag, g), "after dispose: flag must not retain G (leak)")
        assertFalse(isDependent(a, g), "after dispose: a must not retain G (leak)")
        assertFalse(isDependent(b, g), "after dispose: b must not retain G (leak)")
    }
}
