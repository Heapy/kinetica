package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DerivedCellRenderClosureRefreshTest {
    @Test
    fun slotMemoizedDerivedUsesCurrentRenderClosure() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val source = store(1)
        val captured = mutableListOf<Cell<Int>>()

        fun render(multiplier: Int): String =
            runtime.render(scope) {
                val valueCell = derived { source.value * multiplier }
                captured += valueCell
                text(valueCell.value.toString())
            }.tree.let { node -> (node as TextNode).value }

        assertEquals("2", render(multiplier = 2))
        assertEquals("3", render(multiplier = 3))
        assertSame(
            captured[0],
            captured[1],
            "derived{} should keep the slotted cell identity while refreshing its render closure.",
        )
    }
}
