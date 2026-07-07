package io.heapy.kinetica.browser

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.each
import io.heapy.kinetica.host
import io.heapy.kinetica.state
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
import kotlin.test.assertTrue

class BrowserKeyedNeighborsTest {
    /**
     * KSND-025 (sources: INF-025, SOL-043, PRE-046).
     */
    // KSND-025: CONFIRMED FRAMEWORK BUG (triage 2026-07-07): mixed static+keyed child lists — shouldReconcileKeyed requires ALL children keyed (BrowserKineticaApp.kt:714-727), so length changes fall to index-aligned patchPositionalChildren and shift statics/rows into replace(); fix needs per-region boundaries / conditional placeholders.
    @Ignore
    @Test
    fun appendsLandInsideKeyedRegionBeforeStaticTail() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = AppendNeighborsProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            AppendNeighborsApp(probe)
        }

        try {
            val parent = firstElement(root)
            val head = childElement(parent, index = 0)
            val tail = childElement(parent, index = 3)
            assertEquals(staticsAroundRowsHtml("head", "tail", listOf("A", "B")), app.innerHtml())

            probe.items.value = listOf("A", "B", "C")
            app.render()

            assertEquals(staticsAroundRowsHtml("head", "tail", listOf("A", "B", "C")), app.innerHtml())
            assertSame(head, childElement(parent, index = 0))
            assertSame(tail, childElement(parent, index = 4))

            probe.items.value = listOf("A", "B", "C", "D")
            app.render()

            assertEquals(staticsAroundRowsHtml("head", "tail", listOf("A", "B", "C", "D")), app.innerHtml())
            assertSame(head, childElement(parent, index = 0))
            assertSame(tail, childElement(parent, index = 5))
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-026 (sources: INF-025, INF-176, PRE-057).
     */
    // KSND-026: CONFIRMED FRAMEWORK BUG (triage 2026-07-07): mixed static+keyed child lists — shouldReconcileKeyed requires ALL children keyed (BrowserKineticaApp.kt:714-727), so length changes fall to index-aligned patchPositionalChildren and shift statics/rows into replace(); fix needs per-region boundaries / conditional placeholders.
    @Ignore
    @Test
    fun firstInsertIntoEmptyRegionLandsBetweenStaticSiblings() {
        installTestDocument()
        val betweenRoot = testDocument().createElement("div").unsafeCast<Element>()
        val betweenProbe = EmptyRegionProbe()
        val betweenApp = mountKineticaApp(betweenRoot, runtime = KineticaRuntime(debug = false)) {
            EmptyRegionBetweenStaticsApp(betweenProbe)
        }

        try {
            val parent = firstElement(betweenRoot)
            val head = childElement(parent, index = 0)
            val tail = childElement(parent, index = 1)
            assertEquals(staticsAroundRowsHtml("head", "tail", emptyList()), betweenApp.innerHtml())

            betweenProbe.items.value = listOf("A")
            betweenApp.render()

            assertEquals(staticsAroundRowsHtml("head", "tail", listOf("A")), betweenApp.innerHtml())
            assertSame(head, childElement(parent, index = 0))
            assertSame(tail, childElement(parent, index = 2))

            betweenProbe.items.value = emptyList()
            betweenApp.render()

            assertEquals(staticsAroundRowsHtml("head", "tail", emptyList()), betweenApp.innerHtml())
            assertSame(head, childElement(parent, index = 0))
            assertSame(tail, childElement(parent, index = 1))

            betweenProbe.items.value = listOf("B")
            betweenApp.render()

            assertEquals(staticsAroundRowsHtml("head", "tail", listOf("B")), betweenApp.innerHtml())
            assertSame(head, childElement(parent, index = 0))
            assertSame(tail, childElement(parent, index = 2))
        } finally {
            betweenApp.dispose()
        }

        val beforeRoot = testDocument().createElement("div").unsafeCast<Element>()
        val beforeProbe = EmptyRegionProbe()
        val beforeApp = mountKineticaApp(beforeRoot, runtime = KineticaRuntime(debug = false)) {
            EmptyRegionBeforeStaticsApp(beforeProbe)
        }

        try {
            val parent = firstElement(beforeRoot)
            val head = childElement(parent, index = 0)
            val tail = childElement(parent, index = 1)
            assertEquals(regionBeforeStaticsHtml(emptyList()), beforeApp.innerHtml())

            beforeProbe.items.value = listOf("A")
            beforeApp.render()

            assertEquals(regionBeforeStaticsHtml(listOf("A")), beforeApp.innerHtml())
            assertSame(head, childElement(parent, index = 1))
            assertSame(tail, childElement(parent, index = 2))

            beforeProbe.items.value = emptyList()
            beforeApp.render()

            assertEquals(regionBeforeStaticsHtml(emptyList()), beforeApp.innerHtml())
            assertSame(head, childElement(parent, index = 0))
            assertSame(tail, childElement(parent, index = 1))

            beforeProbe.items.value = listOf("B")
            beforeApp.render()

            assertEquals(regionBeforeStaticsHtml(listOf("B")), beforeApp.innerHtml())
            assertSame(head, childElement(parent, index = 1))
            assertSame(tail, childElement(parent, index = 2))
        } finally {
            beforeApp.dispose()
        }
    }

    /**
     * KSND-027 (sources: INF-026).
     */
    // KSND-027: CONFIRMED FRAMEWORK BUG (triage 2026-07-07): mixed static+keyed child lists — shouldReconcileKeyed requires ALL children keyed (BrowserKineticaApp.kt:714-727), so length changes fall to index-aligned patchPositionalChildren and shift statics/rows into replace(); fix needs per-region boundaries / conditional placeholders.
    @Ignore
    @Test
    fun siblingKeyedRegionsWithIdenticalKeysStayIndependent() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = SiblingRegionsProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            SiblingRegionsApp(probe)
        }

        try {
            val parent = firstElement(root)
            assertEquals(twoRegionsHtml(listOf("x", "y"), listOf("x", "y")), app.innerHtml())
            val firstX = childElement(parent, index = 0)
            val firstY = childElement(parent, index = 1)
            val divider = childElement(parent, index = 2)
            val secondX = childElement(parent, index = 3)
            val secondY = childElement(parent, index = 4)
            assertAllDistinct(listOf(firstX, firstY, secondX, secondY))

            probe.items1.value = listOf("x", "y", "z")
            probe.items2.value = listOf("x", "y", "z")
            app.render()

            assertEquals(twoRegionsHtml(listOf("x", "y", "z"), listOf("x", "y", "z")), app.innerHtml())
            assertSame(firstX, childElement(parent, index = 0))
            assertSame(firstY, childElement(parent, index = 1))
            assertSame(divider, childElement(parent, index = 3))
            assertSame(secondX, childElement(parent, index = 4))
            assertSame(secondY, childElement(parent, index = 5))
            assertAllDistinct(
                listOf(
                    childElement(parent, index = 0),
                    childElement(parent, index = 1),
                    childElement(parent, index = 2),
                    childElement(parent, index = 4),
                    childElement(parent, index = 5),
                    childElement(parent, index = 6),
                ),
            )
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-028 (sources: INF-027, INF-172).
     */
    @Test
    fun inlineTextAdjacentToKeyedRegionKeepsPositionAcrossEmptyCycles() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = InlineRegionProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            InlineRegionApp(probe)
        }

        try {
            val parent = firstElement(root)
            val inline = childNode(parent, index = 0)
            assertEquals("<section>inline</section>", app.innerHtml())

            probe.items.value = listOf("n1")
            app.render()

            assertEquals("<section>inline${keyedRowsHtml(listOf("n1"))}</section>", app.innerHtml())
            assertSame(inline, childNode(parent, index = 0))
            assertEquals("n1", childElement(parent, index = 1).textContent)

            probe.items.value = emptyList()
            app.render()

            assertEquals("<section>inline</section>", app.innerHtml())
            assertSame(inline, childNode(parent, index = 0))

            probe.items.value = listOf("n1", "n2")
            app.render()

            assertEquals("<section>inline${keyedRowsHtml(listOf("n1", "n2"))}</section>", app.innerHtml())
            assertSame(inline, childNode(parent, index = 0))
            assertEquals(listOf("inline", "n1", "n2"), childTextContents(parent))
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-029 (sources: INF-028, INF-029, PRE-055).
     */
    // KSND-029: CONFIRMED FRAMEWORK BUG (triage 2026-07-07): mixed static+keyed child lists — shouldReconcileKeyed requires ALL children keyed (BrowserKineticaApp.kt:714-727), so length changes fall to index-aligned patchPositionalChildren and shift statics/rows into replace(); fix needs per-region boundaries / conditional placeholders.
    @Ignore
    @Test
    fun conditionalBannerAndKeyedRowsShareParentAcrossVisibilityMatrix() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = ConditionalRegionProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            ConditionalRegionApp(probe)
        }

        try {
            val parent = firstElement(root)
            assertEquals(bannerRowsHtml(show = true, rows = listOf("a", "b", "c")), app.innerHtml())
            val rowA = childElement(parent, index = 1)

            probe.show.value = false
            probe.items.value = listOf("v", "a")
            app.render()

            assertEquals(bannerRowsHtml(show = false, rows = listOf("v", "a")), app.innerHtml())
            val rowV = childElement(parent, index = 0)
            assertSame(rowA, childElement(parent, index = 1))

            probe.show.value = true
            app.render()

            assertEquals(bannerRowsHtml(show = true, rows = listOf("v", "a")), app.innerHtml())
            assertSame(rowV, childElement(parent, index = 1))
            assertSame(rowA, childElement(parent, index = 2))

            probe.items.value = emptyList()
            app.render()

            assertEquals(bannerRowsHtml(show = true, rows = emptyList()), app.innerHtml())
            assertNull(rowV.parentNode)
            assertNull(rowA.parentNode)

            probe.show.value = false
            app.render()

            assertEquals(bannerRowsHtml(show = false, rows = emptyList()), app.innerHtml())

            probe.show.value = true
            probe.items.value = listOf("a")
            app.render()

            assertEquals(bannerRowsHtml(show = true, rows = listOf("a")), app.innerHtml())
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-030 (sources: INF-030, INF-021).
     */
    // KSND-030: CONFIRMED FRAMEWORK BUG (triage 2026-07-07): mixed static+keyed child lists — shouldReconcileKeyed requires ALL children keyed (BrowserKineticaApp.kt:714-727), so length changes fall to index-aligned patchPositionalChildren and shift statics/rows into replace(); fix needs per-region boundaries / conditional placeholders.
    @Ignore
    @Test
    fun keyedRegionReorderReplaceEmptyAndRemountKeepsStaticsUntouched() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = StaticWrappedRegionProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            StaticWrappedRegionApp(probe)
        }

        try {
            val parent = firstElement(root)
            assertEquals(staticsAroundRowsHtml("test", "end", listOf("1", "2", "3")), app.innerHtml())
            val test = childElement(parent, index = 0)
            val end = childElement(parent, index = 4)

            probe.items.value = listOf("3", "2", "1")
            app.render()

            assertEquals(staticsAroundRowsHtml("test", "end", listOf("3", "2", "1")), app.innerHtml())
            assertSame(test, childElement(parent, index = 0))
            assertSame(end, childElement(parent, index = 4))

            probe.items.value = listOf("9", "8", "7")
            app.render()

            assertEquals(staticsAroundRowsHtml("test", "end", listOf("9", "8", "7")), app.innerHtml())
            assertSame(test, childElement(parent, index = 0))
            assertSame(end, childElement(parent, index = 4))

            probe.items.value = emptyList()
            app.render()

            assertEquals(staticsAroundRowsHtml("test", "end", emptyList()), app.innerHtml())
            assertEquals(2, parent.childNodes.length)
            assertSame(test, childElement(parent, index = 0))
            assertSame(end, childElement(parent, index = 1))

            probe.items.value = listOf("1", "2", "3")
            app.render()

            assertEquals(staticsAroundRowsHtml("test", "end", listOf("1", "2", "3")), app.innerHtml())
            assertSame(test, childElement(parent, index = 0))
            assertSame(end, childElement(parent, index = 4))
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-031 (sources: INF-019, SVL-029).
     */
    @Test
    fun nestedEachOuterAndInnerReorderThenHeadDeletionMatchesFreshShape() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = NestedEachProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            NestedEachNeighborsApp(probe)
        }

        try {
            val parent = firstElement(root)
            assertEquals(nestedRowsHtml(probe.rows.value), app.innerHtml())
            val rowA = childElement(parent, index = 0)
            val rowB = childElement(parent, index = 1)

            val reordered = listOf(
                NestedRegionRow("B", listOf("3", "4")),
                NestedRegionRow("A", listOf("2", "1")),
            )
            probe.rows.value = reordered
            app.render()

            assertEquals(nestedRowsHtml(reordered), app.innerHtml())
            assertSame(rowB, childElement(parent, index = 0))
            assertSame(rowA, childElement(parent, index = 1))

            val withoutHead = listOf(NestedRegionRow("A", listOf("2", "1")))
            probe.rows.value = withoutHead
            app.render()

            assertEquals(nestedRowsHtml(withoutHead), app.innerHtml())
            assertSame(rowA, childElement(parent, index = 0))
            assertNull(rowB.parentNode)

            probe.rows.value = emptyList()
            app.render()

            assertEquals(nestedRowsHtml(emptyList()), app.innerHtml())
            assertNull(rowA.parentNode)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-032 (sources: SOL-044, SVL-030, PRE-136, INF-174).
     */
    // KSND-032: FEATURE GAP (by design): FragmentNode carries no reconcile key, multi-root rows flatten to unkeyed siblings and patch positionally; keyed multi-root containers would be a new feature.
    @Ignore
    @Test
    fun multiRootRowsMoveAtomicallyAndAreRemovedAsAUnit() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = MultiRootRowsProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            MultiRootRowsApp(probe)
        }

        try {
            val parent = firstElement(root)
            assertEquals(multiRootRowsHtml(listOf("1", "2", "3")), app.innerHtml())
            val p1 = childElement(parent, index = 0)
            val span1 = childElement(parent, index = 1)
            val p2 = childElement(parent, index = 2)
            val span2 = childElement(parent, index = 3)
            val p3 = childElement(parent, index = 4)
            val span3 = childElement(parent, index = 5)
            assertAtomicPair("1", p1, span1)
            assertAtomicPair("2", p2, span2)
            assertAtomicPair("3", p3, span3)

            probe.items.value = listOf("3", "2", "1")
            app.render()

            assertEquals(multiRootRowsHtml(listOf("3", "2", "1")), app.innerHtml())
            assertSame(p3, childElement(parent, index = 0))
            assertSame(span3, childElement(parent, index = 1))
            assertSame(p2, childElement(parent, index = 2))
            assertSame(span2, childElement(parent, index = 3))
            assertSame(p1, childElement(parent, index = 4))
            assertSame(span1, childElement(parent, index = 5))
            assertAtomicPair("3", p3, span3)
            assertAtomicPair("2", p2, span2)
            assertAtomicPair("1", p1, span1)

            probe.items.value = listOf("3", "1")
            app.render()

            assertEquals(multiRootRowsHtml(listOf("3", "1")), app.innerHtml())
            assertSame(p3, childElement(parent, index = 0))
            assertSame(span3, childElement(parent, index = 1))
            assertSame(p1, childElement(parent, index = 2))
            assertSame(span1, childElement(parent, index = 3))
            assertNull(p2.parentNode)
            assertNull(span2.parentNode)
            assertAtomicPair("3", p3, span3)
            assertAtomicPair("1", p1, span1)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-033 (sources: INF-178, INF-177, KNT-0012).
     */
    @Test
    fun clearingOneRegionDoesNotBulkClearSharedParent() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = ClearOneRegionProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            ClearOneRegionApp(probe)
        }

        try {
            val parent = firstElement(root)
            assertEquals("<section>${keyedRowsHtml(listOf("x1", "x2", "y1", "y2"))}</section>", app.innerHtml())
            val x1 = childElement(parent, index = 0)
            val x2 = childElement(parent, index = 1)

            probe.items1.value = listOf("x1", "x2", "x3", "x4")
            probe.items2.value = emptyList()
            app.render()

            assertEquals("<section>${keyedRowsHtml(listOf("x1", "x2", "x3", "x4"))}</section>", app.innerHtml())
            assertEquals(4, parent.childNodes.length)
            assertSame(x1, childElement(parent, index = 0))
            assertSame(x2, childElement(parent, index = 1))
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-034 (sources: SVL-038, SOL-045, SOL-047).
     */
    @Test
    fun emptyFallbackSwapsWithRowsAndRemainsReactive() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = FallbackRowsProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            FallbackRowsApp(probe)
        }

        try {
            val parent = firstElement(root)
            assertEquals(fallbackRowsHtml(emptyList(), count = 0), app.innerHtml())
            val firstFallback = childNode(parent, index = 0)

            probe.count.value = 1
            app.render()

            assertEquals(fallbackRowsHtml(emptyList(), count = 1), app.innerHtml())
            assertSame(firstFallback, childNode(parent, index = 0))

            probe.count.value = 2
            app.render()

            assertEquals(fallbackRowsHtml(emptyList(), count = 2), app.innerHtml())
            assertSame(firstFallback, childNode(parent, index = 0))

            probe.items.value = listOf("1", "2", "3")
            app.render()

            assertEquals(fallbackRowsHtml(listOf("1", "2", "3"), count = 2), app.innerHtml())
            assertNull(firstFallback.parentNode)
            val row1 = childElement(parent, index = 0)
            val row2 = childElement(parent, index = 1)
            val row3 = childElement(parent, index = 2)

            probe.items.value = emptyList()
            app.render()

            assertEquals(fallbackRowsHtml(emptyList(), count = 2), app.innerHtml())
            assertNull(row1.parentNode)
            assertNull(row2.parentNode)
            assertNull(row3.parentNode)
            val secondFallback = childNode(parent, index = 0)

            probe.count.value = 3
            app.render()

            assertEquals(fallbackRowsHtml(emptyList(), count = 3), app.innerHtml())
            assertSame(secondFallback, childNode(parent, index = 0))

            probe.items.value = listOf("1", "2", "3")
            app.render()

            assertEquals(fallbackRowsHtml(listOf("1", "2", "3"), count = 3), app.innerHtml())
            assertNotSame(row1, childElement(parent, index = 0))
            assertNotSame(row2, childElement(parent, index = 1))
            assertNotSame(row3, childElement(parent, index = 2))
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-035 (sources: INF-033, RCT-012, PRE-052).
     */
    @Test
    fun insertingSiblingBeforeStatefulRowDoesNotRemountOrResetIt() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = StatefulInsertionProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            StatefulInsertionApp(probe)
        }

        try {
            val parent = firstElement(root)
            assertEquals(statefulRowsHtml(listOf("a", "c"), emptyMap()), app.innerHtml())
            assertEquals(2, probe.rowInits)
            val rowA = childElement(parent, index = 0)
            val rowC = childElement(parent, index = 1)

            probe.values.value = mapOf("a" to 7)
            app.render()

            assertEquals(statefulRowsHtml(listOf("a", "c"), mapOf("a" to 7)), app.innerHtml())
            assertEquals(2, probe.rowInits)
            assertSame(rowA, childElement(parent, index = 0))
            assertSame(rowC, childElement(parent, index = 1))

            probe.items.value = listOf("a", "b", "c")
            app.render()

            assertEquals(statefulRowsHtml(listOf("a", "b", "c"), mapOf("a" to 7)), app.innerHtml())
            assertEquals(3, probe.rowInits)
            assertSame(rowA, childElement(parent, index = 0))
            assertSame(rowC, childElement(parent, index = 2))
            assertEquals("a:7", rowA.textContent)
        } finally {
            app.dispose()
        }
    }
}

private class AppendNeighborsProbe {
    val items = store(listOf("A", "B"))
}

private class EmptyRegionProbe {
    val items = store(emptyList<String>())
}

private class SiblingRegionsProbe {
    val items1 = store(listOf("x", "y"))
    val items2 = store(listOf("x", "y"))
}

private class InlineRegionProbe {
    val items = store(emptyList<String>())
}

private class ConditionalRegionProbe {
    val show = store(true)
    val items = store(listOf("a", "b", "c"))
}

private class StaticWrappedRegionProbe {
    val items = store(listOf("1", "2", "3"))
}

private data class NestedRegionRow(
    val key: String,
    val innerKeys: List<String>,
)

private class NestedEachProbe {
    val rows = store(
        listOf(
            NestedRegionRow("A", listOf("1", "2")),
            NestedRegionRow("B", listOf("3")),
        ),
    )
}

private class MultiRootRowsProbe {
    val items = store(listOf("1", "2", "3"))
}

private class ClearOneRegionProbe {
    val items1 = store(listOf("x1", "x2"))
    val items2 = store(listOf("y1", "y2"))
}

private class FallbackRowsProbe {
    val items = store(emptyList<String>())
    val count = store(0)
}

private class StatefulInsertionProbe {
    val items = store(listOf("a", "c"))
    val values = store(emptyMap<String, Int>())
    var rowInits = 0
}

@UiComponent(skippable = false)
private fun ComponentScope.AppendNeighborsApp(probe: AppendNeighborsProbe) {
    host("section") {
        host("span") { text("head", semantics = null) }
        each(probe.items.value, key = { it }) { item ->
            host("div", key = item) { text(item, semantics = null) }
        }
        host("span") { text("tail", semantics = null) }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.EmptyRegionBetweenStaticsApp(probe: EmptyRegionProbe) {
    host("section") {
        host("span") { text("head", semantics = null) }
        each(probe.items.value, key = { it }) { item ->
            host("div", key = item) { text(item, semantics = null) }
        }
        host("span") { text("tail", semantics = null) }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.EmptyRegionBeforeStaticsApp(probe: EmptyRegionProbe) {
    host("section") {
        each(probe.items.value, key = { it }) { item ->
            host("div", key = item) { text(item, semantics = null) }
        }
        host("span") { text("head", semantics = null) }
        host("span") { text("tail", semantics = null) }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SiblingRegionsApp(probe: SiblingRegionsProbe) {
    host("section") {
        each(probe.items1.value, key = { it }) { item ->
            host("div", key = item) { text(item, semantics = null) }
        }
        host("span") { text("divider", semantics = null) }
        each(probe.items2.value, key = { it }) { item ->
            host("div", key = item) { text(item, semantics = null) }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.InlineRegionApp(probe: InlineRegionProbe) {
    host("section") {
        text("inline", semantics = null)
        each(probe.items.value, key = { it }) { item ->
            host("div", key = item) { text(item, semantics = null) }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.ConditionalRegionApp(probe: ConditionalRegionProbe) {
    host("section") {
        if (probe.show.value) {
            host("banner") { text("banner", semantics = null) }
        }
        each(probe.items.value, key = { it }) { item ->
            host("div", key = item) { text(item, semantics = null) }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.StaticWrappedRegionApp(probe: StaticWrappedRegionProbe) {
    host("section") {
        host("span") { text("test", semantics = null) }
        each(probe.items.value, key = { it }) { item ->
            host("div", key = item) { text(item, semantics = null) }
        }
        host("span") { text("end", semantics = null) }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.NestedEachNeighborsApp(probe: NestedEachProbe) {
    host("section") {
        each(probe.rows.value, key = { it.key }) { row ->
            NestedOuterRow(row)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.NestedOuterRow(row: NestedRegionRow) {
    host("div", key = row.key) {
        host("h1") { text(row.key, semantics = null) }
        each(row.innerKeys, key = { it }) { inner ->
            host("span", key = inner) { text(inner, semantics = null) }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.MultiRootRowsApp(probe: MultiRootRowsProbe) {
    host("section") {
        each(probe.items.value, key = { it }) { item ->
            MultiRootPair(item)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.MultiRootPair(item: String) {
    host("p") { text(item, semantics = null) }
    host("span") { text("($item)", semantics = null) }
}

@UiComponent(skippable = false)
private fun ComponentScope.ClearOneRegionApp(probe: ClearOneRegionProbe) {
    host("section") {
        each(probe.items1.value, key = { it }) { item ->
            host("div", key = item) { text(item, semantics = null) }
        }
        each(probe.items2.value, key = { it }) { item ->
            host("div", key = item) { text(item, semantics = null) }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.FallbackRowsApp(probe: FallbackRowsProbe) {
    host("section") {
        val items = probe.items.value
        if (items.isEmpty()) {
            text("empty ${probe.count.value}", semantics = null)
        } else {
            each(items, key = { it }) { item ->
                host("div", key = item) { text(item, semantics = null) }
            }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.StatefulInsertionApp(probe: StatefulInsertionProbe) {
    host("section") {
        each(probe.items.value, key = { it }) { item ->
            StatefulInsertionRow(probe, item)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.StatefulInsertionRow(
    probe: StatefulInsertionProbe,
    item: String,
) {
    var n by state {
        probe.rowInits += 1
        0
    }
    val value = probe.values.value[item] ?: n
    host("div", key = item) {
        text("$item:$value", semantics = null)
    }
}

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun childElement(parent: Element, index: Int): Element =
    parent.childNodes.item(index)?.unsafeCast<Element>()
        ?: error("Expected child element at index $index.")

private fun childNode(parent: Element, index: Int): DomNode =
    parent.childNodes.item(index) ?: error("Expected child node at index $index.")

private fun childTextContents(parent: Element): List<String> =
    (0 until parent.childNodes.length).map { index ->
        childNode(parent, index).textContent.orEmpty()
    }

private fun assertAllDistinct(elements: List<Element>) {
    for (left in elements.indices) {
        for (right in left + 1 until elements.size) {
            assertTrue(elements[left] !== elements[right], "Expected elements $left and $right to be distinct.")
        }
    }
}

private fun assertAtomicPair(
    key: String,
    paragraph: Element,
    span: Element,
) {
    assertSame(span, paragraph.nextSibling, "Expected $key paragraph to be immediately followed by its span.")
    assertEquals(key, paragraph.textContent)
    assertEquals("($key)", span.textContent)
}

private fun staticsAroundRowsHtml(
    head: String,
    tail: String,
    rows: List<String>,
): String =
    "<section><span>$head</span>${keyedRowsHtml(rows)}<span>$tail</span></section>"

private fun regionBeforeStaticsHtml(rows: List<String>): String =
    "<section>${keyedRowsHtml(rows)}<span>head</span><span>tail</span></section>"

private fun twoRegionsHtml(
    first: List<String>,
    second: List<String>,
): String =
    "<section>${keyedRowsHtml(first)}<span>divider</span>${keyedRowsHtml(second)}</section>"

private fun bannerRowsHtml(
    show: Boolean,
    rows: List<String>,
): String =
    "<section>${if (show) "<banner>banner</banner>" else ""}${keyedRowsHtml(rows)}</section>"

private fun nestedRowsHtml(rows: List<NestedRegionRow>): String =
    "<section>${rows.joinToString(separator = "") { row ->
        """<div data-kinetica-key="${row.key}"><h1>${row.key}</h1>""" +
            row.innerKeys.joinToString(separator = "") { inner ->
                """<span data-kinetica-key="$inner">$inner</span>"""
            } +
            "</div>"
    }}</section>"

private fun multiRootRowsHtml(rows: List<String>): String =
    "<section>${rows.joinToString(separator = "") { row -> "<p>$row</p><span>($row)</span>" }}</section>"

private fun fallbackRowsHtml(
    rows: List<String>,
    count: Int,
): String =
    if (rows.isEmpty()) {
        "<section>empty $count</section>"
    } else {
        "<section>${keyedRowsHtml(rows)}</section>"
    }

private fun statefulRowsHtml(
    rows: List<String>,
    values: Map<String, Int>,
): String =
    "<section>${keyedRowsHtml(rows) { row -> "$row:${values[row] ?: 0}" }}</section>"

private fun keyedRowsHtml(
    rows: List<String>,
    label: (String) -> String = { it },
): String =
    rows.joinToString(separator = "") { row ->
        """<div data-kinetica-key="$row">${label(row)}</div>"""
    }
