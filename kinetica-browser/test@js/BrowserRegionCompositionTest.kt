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

private class BrowserRegionCompositionProbe {
    val itemsA = store(listOf("a1", "a2"))
    val itemsB = store(listOf("b1", "b2"))
}

@UiComponent(skippable = false)
private fun ComponentScope.BrowserRegionRowsA(probe: BrowserRegionCompositionProbe) {
    each(probe.itemsA.value, key = { item -> item }) { item ->
        host("div", key = item) {
            text(item, semantics = null)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.BrowserRegionRowsB(probe: BrowserRegionCompositionProbe) {
    each(probe.itemsB.value, key = { item -> item }) { item ->
        host("div", key = item) {
            text(item, semantics = null)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.BrowserRegionCompositionApp(probe: BrowserRegionCompositionProbe) {
    host("section") {
        BrowserRegionRowsA(probe)
        host("span") {
            text("divider", semantics = null)
        }
        BrowserRegionRowsB(probe)
    }
}

class BrowserRegionCompositionTest {
    /**
     * C1: two composed non-skippable `each` regions both record ordinal 0; the unkeyed
     * divider routes through `patchRegionedChildren`, which matches both new regions
     * against the old B-row range and orphans the B DOM.
     */
    @Test
    fun appendingFirstComposedRegionPreservesSecondRegionDom() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = BrowserRegionCompositionProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            BrowserRegionCompositionApp(probe)
        }

        try {
            val section = firstBrowserRegionElement(root)
            assertEquals(regionCompositionHtml(listOf("a1", "a2"), listOf("b1", "b2")), app.innerHtml())
            val b1 = browserRegionChildElement(section, index = 3)
            val b2 = browserRegionChildElement(section, index = 4)

            probe.itemsA.value = listOf("a1", "a2", "a3")
            app.render()

            assertEquals(regionCompositionHtml(listOf("a1", "a2", "a3"), listOf("b1", "b2")), app.innerHtml())
            assertSame(b1, browserRegionChildElement(section, index = 4))
            assertSame(b2, browserRegionChildElement(section, index = 5))
            assertSame(section, b1.parentNode)
            assertSame(section, b2.parentNode)
        } finally {
            app.dispose()
        }
    }
}

private fun firstBrowserRegionElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun browserRegionChildElement(parent: Element, index: Int): Element =
    parent.childNodes.item(index)?.unsafeCast<Element>()
        ?: error("Expected child element at index $index.")

private fun regionCompositionHtml(
    rowsA: List<String>,
    rowsB: List<String>,
): String =
    "<section>${browserRegionRowsHtml(rowsA)}<span>divider</span>${browserRegionRowsHtml(rowsB)}</section>"

private fun browserRegionRowsHtml(rows: List<String>): String =
    rows.joinToString(separator = "") { row ->
        """<div data-kinetica-key="$row">$row</div>"""
    }
