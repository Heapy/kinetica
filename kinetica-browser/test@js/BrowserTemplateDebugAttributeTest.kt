package io.heapy.kinetica.browser

import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.TemplateDefinition
import io.heapy.kinetica.TemplateHole
import io.heapy.kinetica.TemplateHoleKinds
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.templateNode
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserTemplateDebugAttributeTest {
    @Test
    fun debugTemplateDescendantsDoNotInheritPrototypePathOrTagAttributes() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val definition = nestedTemplateDefinition(id = "browser-template-debug-attrs-single")
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = true)) {
            emit(templateNode(definition, values = listOf("Title", "Body")))
        }

        try {
            val templateRoot = firstElement(root)

            assertEquals("", templateRoot.getAttribute(DATA_KINETICA_PATH))
            assertNoDescendantDebugAttributes(templateRoot)
        } finally {
            app.dispose()
        }
    }

    @Test
    fun debugTemplateRepeatedInstancesKeepDescendantsCleanAndRenderValues() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val definition = nestedTemplateDefinition(id = "browser-template-debug-attrs-repeated")
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = true)) {
            emit(
                HostNode(
                    tag = "section",
                    children = listOf(
                        templateNode(definition, values = listOf("Alpha", "One")),
                        templateNode(definition, values = listOf("Beta", "Two")),
                    ),
                ),
            )
        }

        try {
            val section = firstElement(root)
            val firstTemplate = childElement(section, index = 0)
            val secondTemplate = childElement(section, index = 1)

            assertEquals("AlphaOne", firstTemplate.textContent)
            assertEquals("BetaTwo", secondTemplate.textContent)
            assertNoDescendantDebugAttributes(firstTemplate)
            assertNoDescendantDebugAttributes(secondTemplate)
        } finally {
            app.dispose()
        }
    }

    @Test
    fun productionTemplateDescendantsDoNotCarryDebugAttributes() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val definition = nestedTemplateDefinition(id = "browser-template-debug-attrs-production")
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            emit(templateNode(definition, values = listOf("Title", "Body")))
        }

        try {
            val templateRoot = firstElement(root)

            assertNull(templateRoot.getAttribute(DATA_KINETICA_PATH))
            assertNull(templateRoot.getAttribute(DATA_KINETICA_TAG))
            assertNoDescendantDebugAttributes(templateRoot)
        } finally {
            app.dispose()
        }
    }
}

private fun nestedTemplateDefinition(id: String): TemplateDefinition =
    TemplateDefinition(
        id = id,
        skeleton = HostNode(
            tag = "article",
            children = listOf(
                HostNode(
                    tag = "header",
                    children = listOf(
                        HostNode(
                            tag = "h1",
                            children = listOf(TextNode("", semantics = null)),
                        ),
                    ),
                ),
                HostNode(
                    tag = "p",
                    children = listOf(TextNode("", semantics = null)),
                ),
            ),
        ),
        holes = listOf(
            TemplateHole(path = "0.0.0", kind = TemplateHoleKinds.Text),
            TemplateHole(path = "1.0", kind = TemplateHoleKinds.Text),
        ),
    )

private fun assertNoDescendantDebugAttributes(root: Element) {
    val leaks = descendantDebugAttributeLeaks(root)
    assertTrue(
        leaks.isEmpty(),
        "Expected template descendants to have no debug attrs; found ${leaks.joinToString()}",
    )
}

private fun descendantDebugAttributeLeaks(root: Element): List<String> =
    buildList {
        collectDescendantDebugAttributeLeaks(root, parentPath = "root", leaks = this)
    }

private fun collectDescendantDebugAttributeLeaks(
    parent: Element,
    parentPath: String,
    leaks: MutableList<String>,
) {
    for (index in 0 until parent.children.length) {
        val child = parent.children.item(index) ?: continue
        val childPath = "$parentPath.$index"
        for (attribute in DebugAttributes) {
            val value = child.getAttribute(attribute)
            if (value != null) {
                leaks += "$childPath <${child.tagName.lowercase()}> $attribute=$value"
            }
        }
        collectDescendantDebugAttributeLeaks(child, childPath, leaks)
    }
}

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun childElement(parent: Element, index: Int): Element =
    parent.childNodes.item(index)?.unsafeCast<Element>()
        ?: error("Expected child element at index $index.")

private val DebugAttributes = listOf(DATA_KINETICA_PATH, DATA_KINETICA_TAG)

private const val DATA_KINETICA_PATH = "data-kinetica-path"
private const val DATA_KINETICA_TAG = "data-kinetica-tag"
