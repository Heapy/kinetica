package io.heapy.kinetica.browser

import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Node
import io.heapy.kinetica.TextNode
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrowserKeyedOpCountTest {
    /**
     * KSND-014 (sources: PRE-004, INF-002).
     */
    @Test
    fun appendAddsExactlyOneInsert() {
        installTestDocument()
        val ops = installOpLog()

        runListUpdate(
            ops = ops,
            initial = listOf("a", "b"),
            next = listOf("a", "b", "c"),
        ) { parent, _ ->
            assertEquals(1, opCount(ops))
            assertEquals("insertBefore", opAt(ops, 0).op.unsafeCast<String>())
            assertSame(childElement(parent, index = 2), opAt(ops, 0).child.unsafeCast<Element>())
            assertNull(opAt(ops, 0).anchor)
        }
    }

    /**
     * KSND-015 (sources: PRE-006, VUE-091, INF-003).
     */
    @Test
    fun prependAddsOneInsertAndLeavesSurvivorsUntouched() {
        installTestDocument()
        val ops = installOpLog()

        runListUpdate(
            ops = ops,
            initial = listOf("b", "c"),
            next = listOf("a", "b", "c"),
        ) { _, before ->
            assertOpCounts(ops, inserts = 1, removes = 0)
            assertFalse(hasOpChild(ops, before.getValue("b")))
            assertFalse(hasOpChild(ops, before.getValue("c")))
        }
    }

    /**
     * KSND-016 (sources: PRE-005, PRE-007, INF-004).
     */
    @Test
    fun removeHeadAndTailUseOneRemoveEach() {
        installTestDocument()
        val ops = installOpLog()

        runListUpdate(
            ops = ops,
            initial = listOf("z", "a", "b", "c"),
            next = listOf("a", "b", "c"),
        ) { _, before ->
            assertOpCounts(ops, inserts = 0, removes = 1)
            assertSame(before.getValue("z"), opAt(ops, 0).child.unsafeCast<Element>())
            assertEquals("removeChild", opAt(ops, 0).op.unsafeCast<String>())
        }

        runListUpdate(
            ops = ops,
            initial = listOf("a", "b", "c", "d"),
            next = listOf("a", "b", "c"),
        ) { _, before ->
            assertOpCounts(ops, inserts = 0, removes = 1)
            assertSame(before.getValue("d"), opAt(ops, 0).child.unsafeCast<Element>())
            assertEquals("removeChild", opAt(ops, 0).op.unsafeCast<String>())
        }
    }

    /**
     * KSND-017 (sources: PRE-009, PRE-017, INF-004).
     */
    @Test
    fun contiguousMiddleRemovalUsesOnlyRemoves() {
        installTestDocument()
        val ops = installOpLog()

        runListUpdate(
            ops = ops,
            initial = listOf("a", "b", "x", "y", "z", "c", "d"),
            next = listOf("a", "b", "c", "d"),
        ) { _, before ->
            assertOpCounts(ops, inserts = 0, removes = 3)
            assertTrue(hasOpChild(ops, before.getValue("x")))
            assertTrue(hasOpChild(ops, before.getValue("y")))
            assertTrue(hasOpChild(ops, before.getValue("z")))
        }
    }

    /**
     * KSND-018 (sources: PRE-012, PRE-013).
     */
    @Test
    fun adjacentSwapsUseOneMove() {
        installTestDocument()
        val ops = installOpLog()

        runListUpdate(
            ops = ops,
            initial = listOf("a", "b"),
            next = listOf("b", "a"),
        ) { parent, before ->
            assertOpCounts(ops, inserts = 1, removes = 0)
            assertSame(before.getValue("b"), childElement(parent, index = 0))
            assertSame(before.getValue("a"), childElement(parent, index = 1))
        }

        runListUpdate(
            ops = ops,
            initial = listOf("a", "b", "c", "d"),
            next = listOf("a", "c", "b", "d"),
        ) { parent, before ->
            assertOpCounts(ops, inserts = 1, removes = 0)
            assertSame(before.getValue("a"), childElement(parent, index = 0))
            assertSame(before.getValue("c"), childElement(parent, index = 1))
            assertSame(before.getValue("b"), childElement(parent, index = 2))
            assertSame(before.getValue("d"), childElement(parent, index = 3))
        }
    }

    /**
     * KSND-019 (sources: PRE-015, PRE-010, PRE-011).
     */
    @Test
    fun singleElementMovesInLongerListsUseOneMove() {
        installTestDocument()
        val ops = installOpLog()

        runListUpdate(
            ops = ops,
            initial = listOf("a", "b", "c", "d", "e", "f"),
            next = listOf("a", "e", "b", "c", "d", "f"),
        ) { parent, before ->
            assertOpCounts(ops, inserts = 1, removes = 0)
            assertSame(before.getValue("e"), childElement(parent, index = 1))
            assertSame(before.getValue("b"), childElement(parent, index = 2))
            assertSame(before.getValue("c"), childElement(parent, index = 3))
            assertSame(before.getValue("d"), childElement(parent, index = 4))
        }

        runListUpdate(
            ops = ops,
            initial = listOf("a", "b", "c", "d", "e", "f"),
            next = listOf("a", "c", "d", "e", "b", "f"),
        ) { parent, before ->
            assertOpCounts(ops, inserts = 1, removes = 0)
            assertSame(before.getValue("c"), childElement(parent, index = 1))
            assertSame(before.getValue("d"), childElement(parent, index = 2))
            assertSame(before.getValue("e"), childElement(parent, index = 3))
            assertSame(before.getValue("b"), childElement(parent, index = 4))
        }

        runListUpdate(
            ops = ops,
            initial = listOf("b", "c", "d", "a"),
            next = listOf("a", "b", "c", "d"),
        ) { parent, before ->
            assertOpCounts(ops, inserts = 1, removes = 0)
            assertSame(before.getValue("a"), childElement(parent, index = 0))
            assertSame(before.getValue("b"), childElement(parent, index = 1))
            assertSame(before.getValue("c"), childElement(parent, index = 2))
            assertSame(before.getValue("d"), childElement(parent, index = 3))
        }
    }

    /**
     * KSND-020 (sources: PRE-016, VUE-106).
     */
    @Test
    fun fullReverseOfTenUsesAtMostNineMoves() {
        installTestDocument()
        val ops = installOpLog()
        val initial = (0..9).map { index -> index.toString() }

        runListUpdate(
            ops = ops,
            initial = initial,
            next = initial.asReversed(),
        ) { parent, before ->
            assertTrue(opCount(ops) <= 9)
            assertEquals(opCount(ops), countOps(ops, "insertBefore"))
            assertEquals(0, countOps(ops, "removeChild"))
            initial.asReversed().forEachIndexed { index, key ->
                assertSame(before.getValue(key), childElement(parent, index))
            }
        }
    }

    /**
     * KSND-021 (sources: PRE-026).
     */
    @Test
    fun growingWindowDiffUsesTwoRemovesAndTwoInserts() {
        installTestDocument()
        val ops = installOpLog()

        runListUpdate(
            ops = ops,
            initial = listOf("0", "1", "2", "3", "4"),
            next = listOf("2", "3", "4", "5", "6"),
        ) { parent, before ->
            assertOpCounts(ops, inserts = 2, removes = 2)
            assertTrue(hasOpChild(ops, before.getValue("0")))
            assertTrue(hasOpChild(ops, before.getValue("1")))
            assertFalse(hasOpChild(ops, before.getValue("2")))
            assertFalse(hasOpChild(ops, before.getValue("3")))
            assertFalse(hasOpChild(ops, before.getValue("4")))
            assertSame(before.getValue("2"), childElement(parent, index = 0))
            assertSame(before.getValue("3"), childElement(parent, index = 1))
            assertSame(before.getValue("4"), childElement(parent, index = 2))
            assertTrue(hasOpChild(ops, childElement(parent, index = 3)))
            assertTrue(hasOpChild(ops, childElement(parent, index = 4)))
        }
    }

    /**
     * KSND-022 (sources: KNT-0012, INF-178, Playwright bulk-clear self-test).
     */
    @Test
    fun clearAllUsesBulkPathWithoutPerChildRemoves() {
        installTestDocument()
        val ops = installOpLog()

        runListUpdate(
            ops = ops,
            initial = listOf("a", "b", "c", "d", "e"),
            next = emptyList(),
        ) { parent, _ ->
            assertEquals(0, opCount(ops))
            assertEquals(0, parent.childNodes.length)
        }
    }

    /**
     * KSND-023 (sources: PRE-022).
     */
    @Test
    fun middleRangeReplacementLeavesSurvivorsUntouched() {
        installTestDocument()
        val ops = installOpLog()
        val initial = (0 until 30).map { index -> "row-$index" }
        val next = initial.take(10) + (10 until 20).map { index -> "new-$index" } + initial.drop(20)

        runListUpdate(
            ops = ops,
            initial = initial,
            next = next,
        ) { parent, before ->
            assertOpCounts(ops, inserts = 10, removes = 10)
            val survivors = initial.take(10) + initial.drop(20)
            survivors.forEach { key ->
                assertFalse(hasOpChild(ops, before.getValue(key)))
            }
            next.forEachIndexed { index, key ->
                if (key in survivors) {
                    assertSame(before.getValue(key), childElement(parent, index))
                }
            }
        }
    }

    /**
     * KSND-024 (sources: RCT-001, INF-039, VUE-135, PRE-081).
     */
    @Test
    fun identicalRerenderUsesZeroDomOps() {
        installTestDocument()
        val ops = installOpLog()

        runListUpdate(
            ops = ops,
            initial = listOf("a", "b", "c"),
            next = listOf("a", "b", "c"),
        ) { parent, before ->
            assertEquals(0, opCount(ops))
            assertSame(before.getValue("a"), childElement(parent, index = 0))
            assertSame(before.getValue("b"), childElement(parent, index = 1))
            assertSame(before.getValue("c"), childElement(parent, index = 2))
        }
    }
}

private fun runListUpdate(
    ops: dynamic,
    initial: List<String>,
    next: List<String>,
    assertUpdate: (Element, Map<String, Element>) -> Unit,
) {
    val root = testDocument().createElement("div").unsafeCast<Element>()
    testDocument().body.insertBefore(root, null)
    var current: Node = keyedList(initial)
    val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
        emit(current)
    }

    try {
        val parent = firstElement(root)
        val before = elementsByKey(parent, initial)
        clearOps(ops)

        current = keyedList(next)
        app.render()

        assertEquals(expectedHtml(next), app.innerHtml())
        assertUpdate(parent, before)
    } finally {
        app.dispose()
    }
}

// Records renderer-level ops only when the RECEIVER is connected to the document tree.
private fun installOpLog(): dynamic = js(
    """
    (function () {
      var ops = [];
      var proto = globalThis.Element.prototype;
      var origInsert = proto.insertBefore;
      var origRemove = proto.removeChild;
      var suppressInternalRemove = 0;
      proto.insertBefore = function (child, anchor) {
        if (this.isConnected) ops.push({ op: "insertBefore", child: child, anchor: anchor, parent: this });
        suppressInternalRemove++;
        try {
          return origInsert.call(this, child, anchor);
        } finally {
          suppressInternalRemove--;
        }
      };
      proto.removeChild = function (child) {
        if (this.isConnected && suppressInternalRemove === 0) {
          ops.push({ op: "removeChild", child: child, parent: this });
        }
        return origRemove.call(this, child);
      };
      return ops;
    })()
    """,
)

private fun opCount(ops: dynamic): Int = ops.length.unsafeCast<Int>()

private fun clearOps(ops: dynamic) {
    ops.length = 0
}

private fun opAt(ops: dynamic, i: Int): dynamic = ops[i]

private fun keyedList(keys: List<String>): HostNode =
    HostNode(
        tag = "section",
        children = keys.map { key ->
            HostNode(
                tag = "div",
                key = key,
                children = listOf(TextNode(key, semantics = null)),
            )
        },
    )

private fun elementsByKey(parent: Element, keys: List<String>): Map<String, Element> =
    keys.withIndex().associate { (index, key) -> key to childElement(parent, index) }

private fun expectedHtml(keys: List<String>): String =
    "<section>${keys.joinToString(separator = "") { key ->
        """<div data-kinetica-key="$key">$key</div>"""
    }}</section>"

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun childElement(parent: Element, index: Int): Element =
    parent.childNodes.item(index)?.unsafeCast<Element>()
        ?: error("Expected child element at index $index.")

private fun assertOpCounts(ops: dynamic, inserts: Int, removes: Int) {
    assertEquals(inserts + removes, opCount(ops))
    assertEquals(inserts, countOps(ops, "insertBefore"))
    assertEquals(removes, countOps(ops, "removeChild"))
}

private fun countOps(ops: dynamic, kind: String): Int {
    var count = 0
    for (index in 0 until opCount(ops)) {
        if (opAt(ops, index).op.unsafeCast<String>() == kind) {
            count++
        }
    }
    return count
}

private fun hasOpChild(ops: dynamic, element: Element): Boolean {
    for (index in 0 until opCount(ops)) {
        if (opAt(ops, index).child.unsafeCast<Element>() === element) {
            return true
        }
    }
    return false
}
