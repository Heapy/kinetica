package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

private var closureSource = store(1)
private var closureMultiplier = 1
private val closureCaptured = mutableListOf<Cell<Int>>()

@UiComponent(skippable = false)
private fun ComponentScope.ClosureProbe() {
    val valueCell = derived { closureSource.value * closureMultiplier }
    closureCaptured += valueCell
    text(valueCell.value.toString())
}

class DerivedCellRenderClosureRefreshTest {
    @Test
    fun slotMemoizedDerivedUsesCurrentRenderClosure() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        closureSource = store(1)
        closureCaptured.clear()
        val captured = closureCaptured

        fun render(multiplier: Int): String {
            closureMultiplier = multiplier
            return runtime.render(scope) {
                ClosureProbe()
            }.tree.let { node -> (node as TextNode).value }
        }

        assertEquals("2", render(multiplier = 2))
        assertEquals("3", render(multiplier = 3))
        assertSame(
            captured[0],
            captured[1],
            "derived{} should keep the slotted cell identity while refreshing its render closure.",
        )
    }
}
