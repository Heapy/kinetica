package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `derived {}` is slot-memoized: the SAME DerivedCell instance is reused across renders
 * (its lazy cache and version counter survive instead of restarting at zero), while the
 * compute closure is refreshed each render. Frame-era port: `derived` may only be called
 * inside a `@UiComponent` function, so the probe body lives in a top-level component.
 */
private val derivedMemoSource = store(1)
private val derivedMemoCaptured = mutableListOf<Cell<Int>>()
private val derivedMemoVersions = mutableListOf<Long>()

@UiComponent(skippable = false)
private fun ComponentScope.DerivedMemoProbe() {
    val d = derived { derivedMemoSource.value * 2 }
    // Force a read so the derived cell participates as a dependency.
    d.value
    derivedMemoCaptured += d
    derivedMemoVersions += (d as ObservableCell<*>).version
}

class DerivedCellMemoizedAcrossRendersTest {
    @Test
    fun derivedCellInstanceIsMemoizedAcrossRenders() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        derivedMemoSource.value = 1
        derivedMemoCaptured.clear()
        derivedMemoVersions.clear()

        // A single render entry point: each render {} literal is its own region, so slot
        // identity across renders requires rendering through the same content lambda.
        fun render() {
            runtime.render(scope) { DerivedMemoProbe() }
        }

        render()
        render()

        assertSame(
            derivedMemoCaptured[0],
            derivedMemoCaptured[1],
            "derived{} must be slot-memoized: the same DerivedCell instance should be " +
                "reused across renders, but a new instance was allocated each render.",
        )
    }

    @Test
    fun derivedCellVersionSurvivesAcrossRenders() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        derivedMemoSource.value = 1
        derivedMemoCaptured.clear()
        derivedMemoVersions.clear()

        fun render() {
            runtime.render(scope) { DerivedMemoProbe() }
        }

        // First render establishes the derived cell at some version.
        render()
        // Bump the source so a properly-memoized derived cell advances its version.
        derivedMemoSource.value = 2
        // Second render: a memoized cell carries the advanced version forward; a
        // freshly-allocated cell would reset its version counter back to 0.
        render()

        assertTrue(
            derivedMemoVersions[1] > derivedMemoVersions[0],
            "A memoized derived{} should carry its version across renders and advance it " +
                "after a source write, but the version was reset (fresh allocation): " +
                "render0=${derivedMemoVersions[0]}, render1=${derivedMemoVersions[1]}.",
        )
    }
}
