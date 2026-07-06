package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * R01 — `derived()` is not slot-memoized; it recomputes & reallocates every render.
 *
 * `state()` is slot-backed (`slot(slotKey){…}`), so the SAME cell instance is reused
 * across renders. `derived()` returns a bare `DerivedCell(policy, compute)`, so every
 * render mints a brand new instance whose version restarts at 0 and whose cache never
 * survives a render — memoization is dead.
 *
 * These tests assert the DESIRED behavior: the same derived cell is reused across
 * renders (identity), and its version is preserved rather than reset. They FAIL on the
 * current bare-allocation implementation.
 */
class DerivedCellMemoizedAcrossRendersTest {
    @Test
    fun derivedCellInstanceIsMemoizedAcrossRenders() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val source = store(1)

        val captured = mutableListOf<Cell<Int>>()

        fun renderOnce() {
            runtime.render(scope) {
                val d = derived { source.value * 2 }
                // Force a read so the derived cell participates as a dependency.
                d.value
                captured += d
            }
        }

        renderOnce()
        renderOnce()

        assertSame(
            captured[0],
            captured[1],
            "derived{} must be slot-memoized: the same DerivedCell instance should be " +
                "reused across renders, but a new instance was allocated each render.",
        )
    }

    @Test
    fun derivedCellVersionSurvivesAcrossRenders() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val source = store(1)

        val versions = mutableListOf<Long>()

        fun renderOnce() {
            runtime.render(scope) {
                val d = derived { source.value * 2 }
                d.value
                versions += (d as ObservableCell<*>).version
            }
        }

        // First render establishes the derived cell at some version.
        renderOnce()
        // Bump the source so a properly-memoized derived cell advances its version.
        source.value = 2
        // Second render: a memoized cell would carry the advanced version forward;
        // a freshly-allocated cell resets its version counter back to 0.
        renderOnce()

        assertTrue(
            versions[1] > versions[0],
            "A memoized derived{} should carry its version across renders and advance it " +
                "after a source write, but the version was reset (fresh allocation): " +
                "render0=${versions[0]}, render1=${versions[1]}.",
        )
    }
}
