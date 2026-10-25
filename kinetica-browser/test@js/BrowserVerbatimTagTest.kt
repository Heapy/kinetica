package io.heapy.kinetica.browser

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.UiComponent
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals

/** Protects mounting host tags without silently substituting div elements. */
class BrowserVerbatimTagTest {
    @Test
    fun previouslyDenylistedTagsMountAsThemselves() {
        installTestDocument()
        val root = attachedRoot()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            RawScriptTagApp()
        }

        try {
            val element = firstElement(root)
            assertEquals("SCRIPT", element.tagName)
            assertEquals("https://example.test/widget.js", element.getAttribute("src"))
        } finally {
            app.dispose()
        }
    }

    @Test
    fun customTagsMountAsThemselves() {
        installTestDocument()
        val root = attachedRoot()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            RawCustomTagApp()
        }

        try {
            assertEquals("MY-WIDGET", firstElement(root).tagName)
        } finally {
            app.dispose()
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.RawScriptTagApp() {
    emit(
        HostNode(
            tag = "script",
            props = mapOf("src" to "https://example.test/widget.js"),
            children = listOf(TextNode("void 0")),
        ),
    )
}

@UiComponent(skippable = false)
private fun ComponentScope.RawCustomTagApp() {
    emit(HostNode(tag = "my-widget"))
}

private fun attachedRoot(): Element {
    val root = testDocument().createElement("div").unsafeCast<Element>()
    testDocument().body.insertBefore(root, null)
    return root
}

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")
