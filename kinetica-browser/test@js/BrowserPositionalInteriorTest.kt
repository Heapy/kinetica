package io.heapy.kinetica.browser

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.host
import io.heapy.kinetica.store
import io.heapy.kinetica.text
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BrowserPositionalInteriorTest {
    /**
     * C4: a positionally stable same-tag interior child should be patched in place; this is
     * red because patchStaticRange block-replaces the whole changed middle.
     */
    @Test
    fun stableInteriorHostSurvivesTypeChangingNeighbors() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = PositionalInteriorProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            PositionalInteriorApp(probe)
        }

        try {
            val section = firstElement(root)
            assertEquals(positionalInteriorHtml(modeA = true), app.innerHtml())
            val label = childElement(section, index = 1)

            probe.modeA.value = false
            app.render()

            assertEquals(positionalInteriorHtml(modeA = false), app.innerHtml())
            assertSame(
                label,
                childElement(section, index = 1),
                "C4: stable label child should be patched in place between changing neighbors.",
            )
        } finally {
            app.dispose()
        }
    }
}

private class PositionalInteriorProbe {
    val modeA = store(true)
}

@UiComponent(skippable = false)
private fun ComponentScope.PositionalInteriorApp(probe: PositionalInteriorProbe) {
    host("section") {
        if (probe.modeA.value) {
            host("icon") {
                text("icon", semantics = null)
            }
            host("label") {
                text("label", semantics = null)
            }
            host("button") {
                text("button", semantics = null)
            }
        } else {
            host("spinner") {
                text("spinner", semantics = null)
            }
            host("label") {
                text("label", semantics = null)
            }
            host("link") {
                text("link", semantics = null)
            }
        }
    }
}

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun childElement(parent: Element, index: Int): Element =
    parent.childNodes.item(index)?.unsafeCast<Element>()
        ?: error("Expected child element at index $index.")

private fun positionalInteriorHtml(modeA: Boolean): String =
    if (modeA) {
        "<section><icon>icon</icon><label>label</label><button>button</button></section>"
    } else {
        "<section><spinner>spinner</spinner><label>label</label><link>link</link></section>"
    }
