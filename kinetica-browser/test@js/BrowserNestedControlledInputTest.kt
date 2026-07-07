package io.heapy.kinetica.browser

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.checkbox
import io.heapy.kinetica.each
import io.heapy.kinetica.host
import io.heapy.kinetica.store
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class BrowserNestedControlledInputTest {
    /**
     * C5: a controlled input nested under a memoized surviving wrapper should resync; this is
     * red because the wrapper identity short-circuit skips the nested checkbox patch.
     */
    @Test
    fun shrinkingWrappedControlledCheckboxListResyncsSurvivor() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = NestedControlledInputProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            NestedControlledInputApp(probe)
        }

        try {
            val section = firstElement(root)
            val firstRow = childElement(section, index = 0)
            val firstCheckbox = childInput(firstRow, index = 0)
            firstCheckbox.checked = true

            probe.rows.value = listOf("r1")
            app.render()

            assertEquals(1, section.childNodes.length)
            assertSame(
                firstRow,
                childElement(section, index = 0),
                "C5: surviving keyed wrapper row should keep DOM identity.",
            )
            assertFalse(
                childInput(childElement(section, index = 0), index = 0).checked,
                "C5: nested controlled checkbox should resync to rendered checked=false.",
            )
        } finally {
            app.dispose()
        }
    }
}

private class NestedControlledInputProbe {
    val rows = store(listOf("r1", "r2"))
}

@UiComponent(skippable = false)
private fun ComponentScope.NestedControlledInputApp(probe: NestedControlledInputProbe) {
    host("section") {
        each(probe.rows.value, key = { row -> row }) { row ->
            host("row", key = row) {
                checkbox(checked = false, semantics = null)
            }
        }
    }
}

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun childElement(parent: Element, index: Int): Element =
    parent.childNodes.item(index)?.unsafeCast<Element>()
        ?: error("Expected child element at index $index.")

private fun childInput(parent: Element, index: Int): HTMLInputElement =
    childElement(parent, index).unsafeCast<HTMLInputElement>()
