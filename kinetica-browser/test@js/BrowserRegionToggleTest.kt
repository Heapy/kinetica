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

class BrowserRegionToggleTest {
    /**
     * C3: a persistent static sibling should survive adjacent each presence toggles; this is
     * red because omitting the each changes the static-gap key and remounts the header.
     */
    @Test
    fun staticSiblingSurvivesAdjacentEachPresenceToggle() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = RegionToggleProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            RegionToggleApp(probe)
        }

        try {
            val section = firstElement(root)
            assertEquals(regionToggleHtml(show = true, rows = listOf("a", "b")), app.innerHtml())
            val header = childElement(section, index = 0)

            probe.show.value = false
            app.render()
            val hiddenHeader = childElement(section, index = 0)

            probe.show.value = true
            app.render()
            val restoredHeader = childElement(section, index = 0)

            assertEquals(regionToggleHtml(show = true, rows = listOf("a", "b")), app.innerHtml())
            assertSame(
                header,
                hiddenHeader,
                "C3: header should survive when the adjacent each call is structurally omitted.",
            )
            assertSame(
                header,
                restoredHeader,
                "C3: header should also survive when the adjacent each call is restored.",
            )
        } finally {
            app.dispose()
        }
    }
}

private class RegionToggleProbe {
    val show = store(true)
    val items = store(listOf("a", "b"))
}

@UiComponent(skippable = false)
private fun ComponentScope.RegionToggleApp(probe: RegionToggleProbe) {
    host("section") {
        host("span", key = "hdr") {
            text("header", semantics = null)
        }
        if (probe.show.value) {
            each(probe.items.value, key = { item -> item }) { item ->
                host("div", key = item) {
                    text(item, semantics = null)
                }
            }
        }
        host("span") {
            text("footer", semantics = null)
        }
    }
}

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun childElement(parent: Element, index: Int): Element =
    parent.childNodes.item(index)?.unsafeCast<Element>()
        ?: error("Expected child element at index $index.")

private fun regionToggleHtml(show: Boolean, rows: List<String>): String =
    "<section><span data-kinetica-key=\"hdr\">header</span>${
        if (show) {
            rows.joinToString(separator = "") { row ->
                """<div data-kinetica-key="$row">$row</div>"""
            }
        } else {
            ""
        }
    }<span>footer</span></section>"
