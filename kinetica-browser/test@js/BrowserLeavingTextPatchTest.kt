package io.heapy.kinetica.browser

import io.heapy.kinetica.HostNode
import io.heapy.kinetica.Node
import io.heapy.kinetica.NodeFlags
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.asLeaving
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserLeavingTextPatchTest {
    @Test
    fun asLeavingSingleTextPatchWrapsLeavingText() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val initial = HostNode(
            tag = "div",
            children = listOf(TextNode("Item", semantics = null)),
            flags = NodeFlags.CHILDREN_SINGLE_TEXT,
        )
        var current: Node = initial
        val app = mountKineticaApp(root) {
            emit(current)
        }

        try {
            current = initial.asLeaving()
            app.render()

            assertEquals(
                "<div data-kinetica-tag=\"div\" data-kinetica-path=\"\" data-kinetica-leaving=\"true\">" +
                    "<span data-kinetica-path=\"0\" data-kinetica-leaving=\"true\">Item</span></div>",
                app.innerHtml(),
            )
        } finally {
            app.dispose()
        }
    }
}
