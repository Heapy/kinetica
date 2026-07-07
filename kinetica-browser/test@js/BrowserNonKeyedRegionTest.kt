package io.heapy.kinetica.browser

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.each
import io.heapy.kinetica.host
import io.heapy.kinetica.store
import io.heapy.kinetica.text
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BrowserNonKeyedRegionTest {
    /**
     * C2: unchanged non-certified each rows should preserve DOM identity; this is red because
     * non-keyed regions currently replace the whole range on every adjacent render.
     */
    @Test
    fun unchangedNonKeyedEachRowsPreserveDomIdentity() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = NonKeyedRegionProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            NonKeyedRegionApp(probe)
        }

        try {
            val section = firstElement(root)
            assertEquals(nonKeyedRegionHtml(label = "tick0", rows = listOf("a", "b", "c")), app.innerHtml())
            val rowA = childElement(section, index = 1)

            probe.label.value = "tick1"
            app.render()

            assertEquals(nonKeyedRegionHtml(label = "tick1", rows = listOf("a", "b", "c")), app.innerHtml())
            assertSame(
                rowA,
                childElement(section, index = 1),
                "C2: unchanged row a should be patched in place when only an adjacent static sibling changes.",
            )
        } finally {
            app.dispose()
        }
    }
}

private class NonKeyedRegionProbe {
    val label = store("tick0")
    val items = store(listOf("a", "b", "c"))
}

@UiComponent(skippable = false)
private fun ComponentScope.NonKeyedRegionApp(probe: NonKeyedRegionProbe) {
    host("section") {
        host("span") {
            text(probe.label.value, semantics = null)
        }
        each(probe.items.value, key = { item -> item }) { item ->
            host("div") {
                text(item, semantics = null)
            }
        }
    }
}

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun childElement(parent: Element, index: Int): Element =
    parent.childNodes.item(index)?.unsafeCast<Element>()
        ?: error("Expected child element at index $index.")

private fun nonKeyedRegionHtml(label: String, rows: List<String>): String =
    "<section><span>$label</span>${rows.joinToString(separator = "") { row -> "<div>$row</div>" }}</section>"
