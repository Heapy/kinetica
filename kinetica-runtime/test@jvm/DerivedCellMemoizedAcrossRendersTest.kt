package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val derivedMemoSource = store(1)
private val derivedMemoCaptured = mutableListOf<Cell<Int>>()
private val derivedMemoVersions = mutableListOf<Long>()

@UiComponent(skippable = false)
private fun ComponentScope.DerivedMemoProbe() {
    val d = derived { derivedMemoSource.value * 2 }
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

        render()
        derivedMemoSource.value = 2
        render()

        assertTrue(
            derivedMemoVersions[1] > derivedMemoVersions[0],
            "A memoized derived{} should carry its version across renders and advance it " +
                "after a source write, but the version was reset (fresh allocation): " +
                "render0=${derivedMemoVersions[0]}, render1=${derivedMemoVersions[1]}.",
        )
    }
}
