package io.heapy.kinetica.browser

import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Node
import io.heapy.kinetica.TemplateDefinition
import io.heapy.kinetica.TemplateHole
import io.heapy.kinetica.TemplateHoleKinds
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.templateNode
import io.heapy.kinetica.toSafeHtml
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class BrowserTemplateKeyAttributeTest {
    @Test
    fun productionTemplateInnerHtmlMatchesSafeHtmlForExplicitKey() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val definition = keyedTemplateDefinition(id = "browser-template-key-snapshot")
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            emit(templateNode(definition, values = listOf("Alpha"), key = "row-1"))
        }

        try {
            assertEquals(app.tree().toSafeHtml(), app.innerHtml())
        } finally {
            app.dispose()
        }
    }

    @Test
    fun productionTemplatePatchUpdatesExplicitKey() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val definition = keyedTemplateDefinition(id = "browser-template-key-explicit-transition")
        var current: Node = templateNode(definition, values = listOf("Alpha"), key = "a")
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            emit(current)
        }

        try {
            current = templateNode(definition, values = listOf("Beta"), key = "b")
            app.render()

            val element = firstElement(root)
            assertEquals("b", element.getAttribute(DATA_KINETICA_KEY))
            assertEquals("Beta", element.textContent)
        } finally {
            app.dispose()
        }
    }

    @Test
    fun templatePatchRestoresSkeletonFallbackWhenExplicitKeyIsCleared() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val definition = keyedTemplateDefinition(
            id = "browser-template-key-fallback-transition",
            skeletonKey = "skeleton",
        )
        var current: Node = templateNode(definition, values = listOf("Alpha"), key = "a")
        val app = mountKineticaApp(root) {
            emit(current)
        }

        try {
            current = templateNode(definition, values = listOf("Fallback"))
            app.render()

            assertEquals("skeleton", firstElement(root).getAttribute(DATA_KINETICA_KEY))
        } finally {
            app.dispose()
        }
    }

    @Test
    fun templatePatchRemovesKeyWhenNextEffectiveKeyIsNull() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val definitionWithSkeletonKey = keyedTemplateDefinition(
            id = "browser-template-key-null-transition",
            skeletonKey = "skeleton",
        )
        val definitionWithoutKey = keyedTemplateDefinition(id = "browser-template-key-null-transition")
        var current: Node = templateNode(definitionWithSkeletonKey, values = listOf("Skeleton"))
        val app = mountKineticaApp(root) {
            emit(current)
        }

        try {
            current = templateNode(definitionWithoutKey, values = listOf("None"))
            app.render()

            assertNull(firstElement(root).getAttribute(DATA_KINETICA_KEY))
        } finally {
            app.dispose()
        }
    }

    @Test
    fun keyedTemplateReorderMovesExistingDomElementAndKeepsKeyAttribute() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val definition = keyedTemplateDefinition(id = "browser-template-key-reorder")
        var current: Node = templateList(
            definition,
            listOf("first" to "Alpha", "second" to "Beta"),
        )
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            emit(current)
        }

        try {
            val parent = firstElement(root)
            val firstRow = childElement(parent, index = 0)

            current = templateList(
                definition,
                listOf("second" to "Beta", "first" to "Alpha"),
            )
            app.render()

            assertEquals("second", childElement(parent, index = 0).getAttribute(DATA_KINETICA_KEY))
            val movedRow = childElement(parent, index = 1)
            assertSame(firstRow, movedRow)
            assertEquals("first", movedRow.getAttribute(DATA_KINETICA_KEY))
        } finally {
            app.dispose()
        }
    }
}

private fun keyedTemplateDefinition(
    id: String,
    skeletonKey: String? = null,
): TemplateDefinition =
    TemplateDefinition(
        id = id,
        skeleton = HostNode(
            tag = "div",
            children = listOf(TextNode("", semantics = null)),
            key = skeletonKey,
        ),
        holes = listOf(TemplateHole(path = "0", kind = TemplateHoleKinds.Text)),
    )

private fun templateList(
    definition: TemplateDefinition,
    rows: List<Pair<String, String>>,
): HostNode =
    HostNode(
        tag = "section",
        children = rows.map { (key, label) ->
            templateNode(definition, values = listOf(label), key = key)
        },
    )

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun childElement(parent: Element, index: Int): Element =
    parent.childNodes.item(index)?.unsafeCast<Element>()
        ?: error("Expected child element at index $index.")

private const val DATA_KINETICA_KEY = "data-kinetica-key"
