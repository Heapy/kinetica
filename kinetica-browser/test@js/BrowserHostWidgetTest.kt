package io.heapy.kinetica.browser

import io.heapy.kinetica.*
import io.heapy.kinetica.render.*
import org.w3c.dom.Element
import kotlin.test.*

class BrowserHostWidgetTest {
    @Test fun retainedWidgetOwnsChildrenAndDisposesWhenBulkCleared() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        var shown = true
        var value = "one"
        var created = 0
        var disposed = 0
        val factory = HostWidgetFactory<Element> {
            created++
            object : HostWidget<Element> {
                override val view = testDocument().createElement("div").unsafeCast<Element>()
                init { view.insertBefore(testDocument().createElement("canvas").unsafeCast<Element>(), null) }
                override fun update(node: HostNode) { view.setAttribute("value", node.props.getValue("value")) }
                override fun dispose() { disposed++ }
            }
        }
        val app = mountKineticaApp(root, hostWidgets = mapOf("custom" to factory)) {
            column { if (shown) host("custom", mapOf("value" to value), semantics = Semantics(label = "Custom")) }
        }
        val widget = root.firstChild!!.firstChild
        value = "two"; app.render()
        assertSame(widget, root.firstChild!!.firstChild)
        assertEquals(1, created)
        assertEquals(1, widget!!.childNodes.length)
        shown = false; app.render()
        assertEquals(1, disposed)
        app.dispose(); assertEquals(1, disposed)
    }

    @Test fun customHostInsideTemplateIsMountedOnceAndDisposedOnce() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        var created = 0
        var disposed = 0
        val factory = HostWidgetFactory<Element> {
            created++
            object : HostWidget<Element> {
                override val view = testDocument().createElement("canvas").unsafeCast<Element>()
                override fun update(node: HostNode) {}
                override fun dispose() { disposed++ }
            }
        }
        val definition = TemplateDefinition("custom-template", HostNode("div", children = listOf(HostNode("custom"))), emptyList())
        val app = mountKineticaApp(root, hostWidgets = mapOf("custom" to factory)) {
            column { emit(TemplateNode(definition, emptyList())) }
        }
        app.render(); app.render()
        assertEquals(1, created)
        app.dispose(); app.dispose()
        assertEquals(1, disposed)
    }
}
