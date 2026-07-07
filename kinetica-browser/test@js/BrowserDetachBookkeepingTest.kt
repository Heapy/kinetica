package io.heapy.kinetica.browser

import io.heapy.kinetica.ClientRef
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.Node
import io.heapy.kinetica.TemplateDefinition
import io.heapy.kinetica.TemplateHole
import io.heapy.kinetica.TemplateHoleKinds
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.event
import io.heapy.kinetica.hostEvent
import io.heapy.kinetica.templateNode
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserDetachBookkeepingTest {
    @Test
    fun keyedHostRemovalDetachesNestedClientRef() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        var current: Node = mixedClientRefList("keep" to false, "remove" to true)
        val app = mountKineticaApp(root) {
            emit(current)
        }

        try {
            assertEquals(1, app.clientRefCountForTests)

            current = mixedClientRefList("keep" to false)
            app.render()

            assertEquals(0, app.clientRefCountForTests)
        } finally {
            app.dispose()
        }
    }

    @Test
    fun bulkClearDetachesClientRefsBeforeSkip() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        var current: Node = clientRefList("first", "second", "third")
        val app = mountKineticaApp(root) {
            emit(current)
        }

        try {
            assertEquals(3, app.clientRefCountForTests)

            current = clientRefList()
            app.render()

            assertEquals(0, app.clientRefCountForTests)
        } finally {
            app.dispose()
        }
    }

    @Test
    fun clientRefFreeBulkClearRemountsTemplateEvents() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        var showRows = true
        var rowCount = 40
        var clicks = 0
        val app = mountKineticaApp(root) {
            TemplateEventRows(
                showRows = showRows,
                rowCount = rowCount,
                onClick = { clicks += 1 },
            )
        }

        try {
            assertEquals(0, app.clientRefCountForTests)

            showRows = false
            app.render()
            assertEquals(0, app.clientRefCountForTests)

            rowCount = 1
            showRows = true
            app.render()
            val section = firstElement(root)
            val row = childElement(section, index = 0)
            val button = childElement(row, index = 0)
            button.asDynamic().dispatchEvent(testDomEvent("click"))

            assertEquals(1, clicks)
            assertEquals(0, app.clientRefCountForTests)
        } finally {
            app.dispose()
        }
    }
}

private fun clientRefList(vararg keys: String): HostNode =
    mixedClientRefList(*keys.map { key -> key to true }.toTypedArray())

private fun mixedClientRefList(vararg rows: Pair<String, Boolean>): HostNode =
    HostNode(
        tag = "section",
        children = rows.map { (key, hasClientRef) ->
            HostNode(
                tag = "div",
                key = key,
                children = if (hasClientRef) {
                    listOf(
                        HostNode(
                            tag = "span",
                            children = listOf(ClientRef(componentId = "client-$key")),
                        ),
                    )
                } else {
                    listOf(
                        HostNode(
                            tag = "span",
                            children = listOf(TextNode("row-$key", semantics = null)),
                        ),
                    )
                },
            )
        },
    )

@UiComponent
private fun ComponentScope.TemplateEventRows(
    showRows: Boolean,
    rowCount: Int,
    onClick: () -> Unit,
) {
    val click = hostEvent(onEvent = event { onClick() })
    emit(
        HostNode(
            tag = "section",
            children = if (showRows) {
                (0 until rowCount).map { index ->
                    HostNode(
                        tag = "div",
                        key = "row-$index",
                        children = listOf(
                            templateNode(
                                definition = ClickTemplateDefinition,
                                values = listOf("Row $index", click),
                            ),
                        ),
                    )
                }
            } else {
                emptyList()
            },
        ),
    )
}

private val ClickTemplateDefinition = TemplateDefinition(
    id = "browser-detach-bookkeeping-click",
    skeleton = HostNode(
        tag = "button",
        children = listOf(TextNode("", semantics = null)),
    ),
    holes = listOf(
        TemplateHole(path = "0", kind = TemplateHoleKinds.Text),
        TemplateHole(path = "", kind = TemplateHoleKinds.EventProp, propName = "event:onClick"),
    ),
)

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun childElement(parent: Element, index: Int): Element =
    parent.childNodes.item(index)?.unsafeCast<Element>()
        ?: error("Expected child element at index $index.")
