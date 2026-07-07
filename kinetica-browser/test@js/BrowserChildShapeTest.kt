package io.heapy.kinetica.browser

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.button
import io.heapy.kinetica.each
import io.heapy.kinetica.event
import io.heapy.kinetica.fragment
import io.heapy.kinetica.host
import io.heapy.kinetica.store
import io.heapy.kinetica.text
import org.w3c.dom.Element
import org.w3c.dom.Node as DomNode
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class BrowserChildShapeTest {
    /**
     * KSND-107 (sources: INF-034, RCT-015, VUE-113).
     */
    @Test
    fun textUpdatesMutateSameTextNodeInPlace() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = TextUpdateProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            TextUpdateApp(probe)
        }

        try {
            val parent = firstElement(root)
            assertEquals("<section>one</section>", app.innerHtml())
            assertEquals(1, parent.childNodes.length)
            val textNode = childNode(parent, index = 0).unsafeCast<Any>()
            assertEquals("one", childNode(parent, index = 0).nodeValue)

            probe.msg.value = "two"
            app.render()

            assertEquals("<section>two</section>", app.innerHtml())
            assertEquals(1, parent.childNodes.length)
            assertSame(textNode, childNode(parent, index = 0).unsafeCast<Any>())
            assertEquals("two", childNode(parent, index = 0).nodeValue)

            probe.msg.value = "three"
            app.render()

            assertEquals("<section>three</section>", app.innerHtml())
            assertEquals(1, parent.childNodes.length)
            assertSame(textNode, childNode(parent, index = 0).unsafeCast<Any>())
            assertEquals("three", childNode(parent, index = 0).nodeValue)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-108 (sources: INF-034, INF-171, PRE-044, RCT-002, VUE-089).
     */
    @Test
    fun textHostSwitchAtSamePositionLeavesSiblingsUntouched() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = TextHostSwitchProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            TextHostSwitchApp(probe)
        }

        try {
            val parent = firstElement(root)
            assertEquals(textHostSwitchHtml(showText = true), app.innerHtml())
            assertEquals(3, parent.childNodes.length)
            val leading = childNode(parent, index = 0).unsafeCast<Any>()
            val trailing = childNode(parent, index = 2).unsafeCast<Any>()

            probe.showText.value = false
            app.render()

            assertEquals(textHostSwitchHtml(showText = false), app.innerHtml())
            assertEquals(3, parent.childNodes.length)
            assertSame(leading, childNode(parent, index = 0).unsafeCast<Any>())
            assertSame(trailing, childNode(parent, index = 2).unsafeCast<Any>())

            probe.showText.value = true
            app.render()

            assertEquals(textHostSwitchHtml(showText = true), app.innerHtml())
            assertEquals(3, parent.childNodes.length)
            assertSame(leading, childNode(parent, index = 0).unsafeCast<Any>())
            assertSame(trailing, childNode(parent, index = 2).unsafeCast<Any>())

            probe.showText.value = false
            app.render()

            assertEquals(textHostSwitchHtml(showText = false), app.innerHtml())
            assertEquals(3, parent.childNodes.length)
            assertSame(leading, childNode(parent, index = 0).unsafeCast<Any>())
            assertSame(trailing, childNode(parent, index = 2).unsafeCast<Any>())
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-109 (sources: INF-172, RCT-016, PRE-127).
     */
    // KSND-109: CONFIRMED FRAMEWORK BUG (triage 2026-07-07): mixed static+keyed child lists — shouldReconcileKeyed requires ALL children keyed (BrowserKineticaApp.kt:714-727), so length changes fall to index-aligned patchPositionalChildren and shift statics/rows into replace(); fix needs per-region boundaries / conditional placeholders.
    @Ignore
    @Test
    fun leadingAndTrailingTextTogglesLeaveHostIdentityAndNoOrphans() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = OptionalTextAroundHostProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            OptionalTextAroundHostApp(probe)
        }

        try {
            val parent = firstElement(root)
            assertEquals(optionalTextAroundHostHtml(showA = true, showB = true), app.innerHtml())
            assertEquals(3, parent.childNodes.length)
            val host = childElement(parent, index = 1)

            probe.showA.value = false
            app.render()

            assertEquals(optionalTextAroundHostHtml(showA = false, showB = true), app.innerHtml())
            assertEquals(2, parent.childNodes.length)
            assertSame(host, childElement(parent, index = 0))

            probe.showB.value = false
            app.render()

            assertEquals(optionalTextAroundHostHtml(showA = false, showB = false), app.innerHtml())
            assertEquals(1, parent.childNodes.length)
            assertSame(host, childElement(parent, index = 0))

            probe.showB.value = true
            app.render()

            assertEquals(optionalTextAroundHostHtml(showA = false, showB = true), app.innerHtml())
            assertEquals(2, parent.childNodes.length)
            assertSame(host, childElement(parent, index = 0))

            probe.showA.value = true
            app.render()

            assertEquals(optionalTextAroundHostHtml(showA = true, showB = true), app.innerHtml())
            assertEquals(3, parent.childNodes.length)
            assertSame(host, childElement(parent, index = 1))
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-110 (sources: INF-170, RCT-015).
     */
    @Test
    fun adjacentTextChildrenRemainSeparateAndIndependentlyPatched() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = AdjacentTextProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            AdjacentTextApp(probe)
        }

        try {
            val parent = firstElement(root)
            assertEquals("<section>ab</section>", app.innerHtml())
            assertEquals(2, parent.childNodes.length)
            val first = childNode(parent, index = 0).unsafeCast<Any>()
            val second = childNode(parent, index = 1).unsafeCast<Any>()

            probe.b.value = "B"
            app.render()

            assertEquals("<section>aB</section>", app.innerHtml())
            assertEquals(2, parent.childNodes.length)
            assertSame(first, childNode(parent, index = 0).unsafeCast<Any>())
            assertSame(second, childNode(parent, index = 1).unsafeCast<Any>())
            assertEquals("a", childNode(parent, index = 0).nodeValue)
            assertEquals("B", childNode(parent, index = 1).nodeValue)
            assertEquals("aB", parent.textContent)

            probe.a.value = "A"
            app.render()

            assertEquals("<section>AB</section>", app.innerHtml())
            assertEquals(2, parent.childNodes.length)
            assertSame(first, childNode(parent, index = 0).unsafeCast<Any>())
            assertSame(second, childNode(parent, index = 1).unsafeCast<Any>())
            assertEquals("A", childNode(parent, index = 0).nodeValue)
            assertEquals("B", childNode(parent, index = 1).nodeValue)
            assertEquals("AB", parent.textContent)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-111 (sources: INF-035, PRE-036, PRE-038, RCT-017).
     */
    @Test
    fun emptyStringAndZeroTextKeepAStableTextNode() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = EmptyZeroTextProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            EmptyZeroTextApp(probe)
        }

        try {
            val parent = firstElement(root)
            assertEquals("<section></section>", app.innerHtml())
            assertEquals(1, parent.childNodes.length)
            val textNode = childNode(parent, index = 0).unsafeCast<Any>()
            assertEquals("", childNode(parent, index = 0).nodeValue)

            probe.value.value = "0"
            app.render()

            assertEquals("<section>0</section>", app.innerHtml())
            assertEquals(1, parent.childNodes.length)
            assertSame(textNode, childNode(parent, index = 0).unsafeCast<Any>())
            assertEquals("0", childNode(parent, index = 0).nodeValue)
            assertEquals("0", parent.textContent)

            probe.value.value = ""
            app.render()

            assertEquals("<section></section>", app.innerHtml())
            assertEquals(1, parent.childNodes.length)
            assertSame(textNode, childNode(parent, index = 0).unsafeCast<Any>())
            assertEquals("", childNode(parent, index = 0).nodeValue)
            assertEquals("", parent.textContent)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-112 (sources: RCT-002, PRE-037, VUE-109).
     */
    @Test
    fun switchingHostTagsReplacesElementAndDoesNotLeakAttributesOrEvents() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = HostTagSwitchProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            HostTagSwitchApp(probe)
        }

        try {
            val firstButton = firstElement(root)
            assertEquals("button", tagName(firstButton))
            assertEquals("", firstButton.getAttribute("disabled"))
            firstButton.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(0, probe.clicks)

            probe.showButton.value = false
            app.render()

            val paragraph = firstElement(root)
            assertNotSame(firstButton, paragraph)
            assertEquals("p", tagName(paragraph))
            assertNull(paragraph.getAttribute("disabled"))
            paragraph.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(0, probe.clicks)

            probe.enabled.value = true
            probe.showButton.value = true
            app.render()

            val secondButton = firstElement(root)
            assertNotSame(firstButton, secondButton)
            assertNotSame(paragraph, secondButton)
            assertEquals("button", tagName(secondButton))
            assertNull(secondButton.getAttribute("disabled"))
            secondButton.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(1, probe.clicks)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-113 (sources: INF-177, PRE-120, PRE-126).
     */
    @Test
    fun siblingFragmentsGrowAtTheirOwnBoundary() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = SiblingFragmentsProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            SiblingFragmentsApp(probe)
        }

        try {
            val parent = firstElement(root)
            assertEquals("<section>${keyedRowsHtml(listOf("a1", "a2", "b1", "b2"))}</section>", app.innerHtml())
            assertEquals(4, parent.childNodes.length)
            val b1 = childElement(parent, index = 2)

            probe.aRows.value = listOf("a1", "a2", "a3", "a4")
            probe.bRows.value = listOf("b1")
            app.render()

            val expectedRows = listOf("a1", "a2", "a3", "a4", "b1")
            assertEquals("<section>${keyedRowsHtml(expectedRows)}</section>", app.innerHtml())
            assertEquals(5, parent.childNodes.length)
            assertEquals(expectedRows, childTextContents(parent))
            assertSame(b1, childElement(parent, index = 4))
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-114 (sources: INF-029, RCT-013, PRE-055).
     */
    // KSND-114: CONFIRMED FRAMEWORK BUG (triage 2026-07-07): mixed static+keyed child lists — shouldReconcileKeyed requires ALL children keyed (BrowserKineticaApp.kt:714-727), so length changes fall to index-aligned patchPositionalChildren and shift statics/rows into replace(); fix needs per-region boundaries / conditional placeholders.
    @Ignore
    @Test
    fun independentNullTogglesBetweenFixedSiblingsKeepFixedSiblingIdentity() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = IndependentNullToggleProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            IndependentNullToggleApp(probe)
        }

        try {
            val parent = firstElement(root)
            assertEquals(independentNullToggleHtml(showFirst = true, showSecond = true), app.innerHtml())
            val p1 = childElement(parent, index = 0)
            val firstSpan = childElement(parent, index = 1)
            val p2 = childElement(parent, index = 2)
            val secondSpan = childElement(parent, index = 3)
            val p3 = childElement(parent, index = 4)

            probe.showFirst.value = false
            app.render()

            assertEquals(independentNullToggleHtml(showFirst = false, showSecond = true), app.innerHtml())
            assertSame(p1, childElement(parent, index = 0))
            assertSame(p2, childElement(parent, index = 1))
            assertSame(secondSpan, childElement(parent, index = 2))
            assertSame(p3, childElement(parent, index = 3))

            probe.showSecond.value = false
            app.render()

            assertEquals(independentNullToggleHtml(showFirst = false, showSecond = false), app.innerHtml())
            assertSame(p1, childElement(parent, index = 0))
            assertSame(p2, childElement(parent, index = 1))
            assertSame(p3, childElement(parent, index = 2))

            probe.showSecond.value = true
            app.render()

            assertEquals(independentNullToggleHtml(showFirst = false, showSecond = true), app.innerHtml())
            val secondSpanAgain = childElement(parent, index = 2)
            assertNotSame(secondSpan, secondSpanAgain)
            assertSame(p1, childElement(parent, index = 0))
            assertSame(p2, childElement(parent, index = 1))
            assertSame(p3, childElement(parent, index = 3))

            probe.showFirst.value = true
            app.render()

            assertEquals(independentNullToggleHtml(showFirst = true, showSecond = true), app.innerHtml())
            val firstSpanAgain = childElement(parent, index = 1)
            assertNotSame(firstSpan, firstSpanAgain)
            assertSame(p1, childElement(parent, index = 0))
            assertSame(p2, childElement(parent, index = 2))
            assertSame(secondSpanAgain, childElement(parent, index = 3))
            assertSame(p3, childElement(parent, index = 4))
        } finally {
            app.dispose()
        }
    }
}

private class TextUpdateProbe {
    val msg = store("one")
}

private class TextHostSwitchProbe {
    val showText = store(true)
}

private class OptionalTextAroundHostProbe {
    val showA = store(true)
    val showB = store(true)
}

private class AdjacentTextProbe {
    val a = store("a")
    val b = store("b")
}

private class EmptyZeroTextProbe {
    val value = store("")
}

private class HostTagSwitchProbe {
    val showButton = store(true)
    val enabled = store(false)
    var clicks = 0
}

private class SiblingFragmentsProbe {
    val aRows = store(listOf("a1", "a2"))
    val bRows = store(listOf("b1", "b2"))
}

private class IndependentNullToggleProbe {
    val showFirst = store(true)
    val showSecond = store(true)
}

@UiComponent(skippable = false)
private fun ComponentScope.TextUpdateApp(probe: TextUpdateProbe) {
    host("section") {
        text(probe.msg.value, semantics = null)
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.TextHostSwitchApp(probe: TextHostSwitchProbe) {
    host("section") {
        text("a", semantics = null)
        if (probe.showText.value) {
            text("mid", semantics = null)
        } else {
            host("div") {
                text("mid", semantics = null)
            }
        }
        text("z", semantics = null)
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.OptionalTextAroundHostApp(probe: OptionalTextAroundHostProbe) {
    host("section") {
        if (probe.showA.value) {
            text("A", semantics = null)
        }
        host("div") {
            text("H", semantics = null)
        }
        if (probe.showB.value) {
            text("B", semantics = null)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.AdjacentTextApp(probe: AdjacentTextProbe) {
    host("section") {
        text(probe.a.value, semantics = null)
        text(probe.b.value, semantics = null)
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.EmptyZeroTextApp(probe: EmptyZeroTextProbe) {
    host("section") {
        text(probe.value.value, semantics = null)
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.HostTagSwitchApp(probe: HostTagSwitchProbe) {
    if (probe.showButton.value) {
        button(
            enabled = probe.enabled.value,
            onClick = event { probe.clicks += 1 },
            semantics = null,
        ) {
            text("x", semantics = null)
        }
    } else {
        host("p") {
            text("x", semantics = null)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SiblingFragmentsApp(probe: SiblingFragmentsProbe) {
    host("section") {
        FragmentRows(probe.aRows.value)
        FragmentRows(probe.bRows.value)
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.FragmentRows(rows: List<String>) {
    fragment {
        each(rows, key = { row -> row }) { row ->
            host("div", key = row) {
                text(row, semantics = null)
            }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.IndependentNullToggleApp(probe: IndependentNullToggleProbe) {
    host("section") {
        host("p") {
            text("p1", semantics = null)
        }
        if (probe.showFirst.value) {
            host("span") {
                text("abc", semantics = null)
            }
        }
        host("p") {
            text("p2", semantics = null)
        }
        if (probe.showSecond.value) {
            host("span") {
                text("def", semantics = null)
            }
        }
        host("p") {
            text("p3", semantics = null)
        }
    }
}

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun childElement(parent: Element, index: Int): Element =
    childNode(parent, index).unsafeCast<Element>()

private fun childNode(parent: Element, index: Int): DomNode =
    parent.childNodes.item(index) ?: error("Expected child node at index $index.")

private fun tagName(element: Element): String =
    element.asDynamic().tagName.unsafeCast<String>().lowercase()

private fun childTextContents(parent: Element): List<String> =
    (0 until parent.childNodes.length).map { index ->
        childNode(parent, index).textContent.orEmpty()
    }

private fun textHostSwitchHtml(showText: Boolean): String =
    if (showText) {
        "<section>amidz</section>"
    } else {
        "<section>a<div>mid</div>z</section>"
    }

private fun optionalTextAroundHostHtml(showA: Boolean, showB: Boolean): String =
    "<section>${if (showA) "A" else ""}<div>H</div>${if (showB) "B" else ""}</section>"

private fun keyedRowsHtml(rows: List<String>): String =
    rows.joinToString(separator = "") { row ->
        """<div data-kinetica-key="$row">$row</div>"""
    }

private fun independentNullToggleHtml(showFirst: Boolean, showSecond: Boolean): String =
    "<section><p>p1</p>" +
        "${if (showFirst) "<span>abc</span>" else ""}<p>p2</p>" +
        "${if (showSecond) "<span>def</span>" else ""}<p>p3</p></section>"
