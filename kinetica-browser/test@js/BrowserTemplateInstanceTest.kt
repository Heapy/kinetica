package io.heapy.kinetica.browser

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.TemplateDefinition
import io.heapy.kinetica.TemplateHole
import io.heapy.kinetica.TemplateHoleKinds
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.event
import io.heapy.kinetica.hostEvent
import io.heapy.kinetica.store
import io.heapy.kinetica.templateNode
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class BrowserTemplateInstanceTest {
    /**
     * KSND-123 (sources: INF-134, INF-043, PRE-042).
     */
    @Test
    fun oneDefinitionInstantiatesIndependentDomSubtrees() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = TextTemplateProbe(
            definition = keyedTemplateDefinition(id = "b13-ksnd-123-independent"),
            initialRows = listOf(
                TextTemplateRow(label = "A"),
                TextTemplateRow(label = "B"),
                TextTemplateRow(label = "C"),
            ),
        )
        val app = mountKineticaApp(root) {
            TextTemplateInstancesApp(probe)
        }

        try {
            val section = firstElement(root)
            assertEquals(3, section.childNodes.length)
            val first = childElement(section, index = 0)
            val second = childElement(section, index = 1)
            val third = childElement(section, index = 2)
            assertNotSame(first, second)
            assertNotSame(first, third)
            assertNotSame(second, third)
            assertEquals("A", first.textContent)
            assertEquals("B", second.textContent)
            assertEquals("C", third.textContent)

            first.textContent = "A*"

            assertEquals("A*", first.textContent)
            assertEquals("B", second.textContent)
            assertEquals("C", third.textContent)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-124 (sources: INF-134, SOL-083).
     */
    @Test
    fun perInstanceTextHolesPatchIndependently() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = TextTemplateProbe(
            definition = keyedTemplateDefinition(id = "b13-ksnd-124-text-patch"),
            initialRows = listOf(
                TextTemplateRow(label = "A"),
                TextTemplateRow(label = "B"),
                TextTemplateRow(label = "C"),
            ),
        )
        val app = mountKineticaApp(root) {
            TextTemplateInstancesApp(probe)
        }

        try {
            val section = firstElement(root)
            val first = childElement(section, index = 0)
            val second = childElement(section, index = 1)
            val third = childElement(section, index = 2)

            probe.rows.value = listOf(
                TextTemplateRow(label = "A"),
                TextTemplateRow(label = "B2"),
                TextTemplateRow(label = "C"),
            )
            app.render()

            assertSame(first, childElement(section, index = 0))
            assertSame(second, childElement(section, index = 1))
            assertSame(third, childElement(section, index = 2))
            assertEquals("A", first.textContent)
            assertEquals("B2", second.textContent)
            assertEquals("C", third.textContent)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-125 (sources: INF-075).
     */
    @Test
    fun perInstanceEventHolesDispatchToTheirOwnEventIds() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = ClickTemplateProbe(
            definition = clickableTemplateDefinition(id = "b13-ksnd-125-event-ids"),
            initialRows = listOf(
                ClickTemplateRow(label = "A", clickIndex = 0),
                ClickTemplateRow(label = "B", clickIndex = 1),
                ClickTemplateRow(label = "C", clickIndex = 2),
            ),
        )
        val app = mountKineticaApp(root) {
            ClickTemplateInstancesApp(probe)
        }

        try {
            val section = firstElement(root)
            childElement(section, index = 1).asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(listOf(0, 1, 0), probe.clicks)

            childElement(section, index = 0).asDynamic().dispatchEvent(testDomEvent("click"))
            childElement(section, index = 2).asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(listOf(1, 1, 1), probe.clicks)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-126 (sources: RCT-004, SVL-024).
     */
    @Test
    fun keyedTemplateRowsReorderPreservesIdentityAndHoleBindings() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = TextTemplateProbe(
            definition = keyedTemplateDefinition(id = "b13-ksnd-126-keyed-reorder"),
            initialRows = listOf(
                TextTemplateRow(key = "a", label = "A"),
                TextTemplateRow(key = "b", label = "B"),
                TextTemplateRow(key = "c", label = "C"),
            ),
        )
        val app = mountKineticaApp(root) {
            TextTemplateInstancesApp(probe)
        }

        try {
            val section = firstElement(root)
            val a = childElement(section, index = 0)
            val b = childElement(section, index = 1)
            val c = childElement(section, index = 2)

            probe.rows.value = listOf(
                TextTemplateRow(key = "c", label = "C"),
                TextTemplateRow(key = "a", label = "A"),
                TextTemplateRow(key = "b", label = "B"),
            )
            app.render()

            assertSame(c, childElement(section, index = 0))
            assertSame(a, childElement(section, index = 1))
            assertSame(b, childElement(section, index = 2))
            assertEquals("c", childElement(section, index = 0).getAttribute(DATA_KINETICA_KEY))
            assertEquals("a", childElement(section, index = 1).getAttribute(DATA_KINETICA_KEY))
            assertEquals("b", childElement(section, index = 2).getAttribute(DATA_KINETICA_KEY))

            probe.rows.value = listOf(
                TextTemplateRow(key = "c", label = "C"),
                TextTemplateRow(key = "a", label = "A"),
                TextTemplateRow(key = "b", label = "B2"),
            )
            app.render()

            assertSame(c, childElement(section, index = 0))
            assertSame(a, childElement(section, index = 1))
            assertSame(b, childElement(section, index = 2))
            assertEquals("C", c.textContent)
            assertEquals("A", a.textContent)
            assertEquals("B2", b.textContent)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-127 (sources: INF-134, PRE-081).
     */
    @Test
    fun cloneAttributeMutationDoesNotLeakIntoLaterClones() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = TextTemplateProbe(
            definition = keyedTemplateDefinition(id = "b13-ksnd-127-pristine-clone"),
            initialRows = listOf(TextTemplateRow(label = "A")),
        )
        val app = mountKineticaApp(root) {
            TextTemplateInstancesApp(probe)
        }

        try {
            val section = firstElement(root)
            val first = childElement(section, index = 0)
            first.setAttribute("data-dirty", "1")

            probe.rows.value = listOf(
                TextTemplateRow(label = "A"),
                TextTemplateRow(label = "B"),
            )
            app.render()

            assertSame(first, childElement(section, index = 0))
            val second = childElement(section, index = 1)
            assertEquals("1", first.getAttribute("data-dirty"))
            assertNull(second.getAttribute("data-dirty"))
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-128.
     */
    @Test
    fun unmountingOneTemplateInstanceClearsOnlyItsEventBindings() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = ClickTemplateProbe(
            definition = clickableTemplateDefinition(id = "b13-ksnd-128-remove-one-event"),
            initialRows = listOf(
                ClickTemplateRow(key = "a", label = "A", clickIndex = 0),
                ClickTemplateRow(key = "b", label = "B", clickIndex = 1),
                ClickTemplateRow(key = "c", label = "C", clickIndex = 2),
            ),
        )
        val app = mountKineticaApp(root) {
            ClickTemplateInstancesApp(probe)
        }

        try {
            val section = firstElement(root)
            val a = childElement(section, index = 0)
            val b = childElement(section, index = 1)
            val c = childElement(section, index = 2)
            assertHasBookkeeping(a)
            assertHasBookkeeping(b)
            assertHasBookkeeping(c)

            probe.rows.value = listOf(
                ClickTemplateRow(key = "a", label = "A", clickIndex = 0),
                ClickTemplateRow(key = "c", label = "C", clickIndex = 2),
            )
            app.render()

            assertSame(a, childElement(section, index = 0))
            assertSame(c, childElement(section, index = 1))
            assertNull(b.parentNode)
            assertNull(b.asDynamic().__kinetica)
            assertHasBookkeeping(a)
            assertHasBookkeeping(c)

            childElement(section, index = 0).asDynamic().dispatchEvent(testDomEvent("click"))
            childElement(section, index = 1).asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(listOf(1, 0, 1), probe.clicks)

            b.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(listOf(1, 0, 1), probe.clicks)
        } finally {
            app.dispose()
        }
    }
}

private class TextTemplateProbe(
    val definition: TemplateDefinition,
    initialRows: List<TextTemplateRow>,
) {
    val rows = store(initialRows)
}

private data class TextTemplateRow(
    val label: String,
    val key: String? = null,
)

@UiComponent
private fun ComponentScope.TextTemplateInstancesApp(probe: TextTemplateProbe) {
    emit(
        HostNode(
            tag = "section",
            children = probe.rows.value.map { row ->
                templateNode(probe.definition, values = listOf(row.label), key = row.key)
            },
        ),
    )
}

private class ClickTemplateProbe(
    val definition: TemplateDefinition,
    initialRows: List<ClickTemplateRow>,
) {
    val rows = store(initialRows)
    val clicks = mutableListOf(0, 0, 0)
}

private data class ClickTemplateRow(
    val label: String,
    val clickIndex: Int,
    val key: String? = null,
)

@UiComponent
private fun ComponentScope.ClickTemplateInstancesApp(probe: ClickTemplateProbe) {
    val click0 = hostEvent(onEvent = event { probe.clicks[0] += 1 })
    val click1 = hostEvent(onEvent = event { probe.clicks[1] += 1 })
    val click2 = hostEvent(onEvent = event { probe.clicks[2] += 1 })
    val clicks = listOf(click0, click1, click2)
    emit(
        HostNode(
            tag = "section",
            children = probe.rows.value.map { row ->
                templateNode(
                    definition = probe.definition,
                    values = listOf(row.label, clicks[row.clickIndex]),
                    key = row.key,
                )
            },
        ),
    )
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

private fun clickableTemplateDefinition(id: String): TemplateDefinition =
    TemplateDefinition(
        id = id,
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

private fun assertHasBookkeeping(element: Element) {
    assertNotNull(element.asDynamic().__kinetica)
}

private const val DATA_KINETICA_KEY = "data-kinetica-key"
