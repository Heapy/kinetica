package io.heapy.kinetica.browser

import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Node
import io.heapy.kinetica.TextNode
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BrowserKeyedScratchCharacterizationTest {
    @Test
    fun nestedKeyedReconciliationReordersOuterAndInnerRowsByIdentity() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val initialRows = listOf(
            NestedRow("alpha", listOf("a-1", "a-2", "a-3")),
            NestedRow("beta", listOf("b-1", "b-2", "b-3")),
            NestedRow("gamma", listOf("g-1", "g-2", "g-3")),
        )
        val nextRows = listOf(
            NestedRow("gamma", listOf("g-3", "g-1", "g-2")),
            NestedRow("alpha", listOf("a-2", "a-3", "a-1")),
            NestedRow("beta", listOf("b-2", "b-1", "b-3")),
        )
        var current: Node = nestedRows(initialRows)
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            emit(current)
        }

        try {
            val parent = firstElement(root)
            val alphaRow = childElement(parent, index = 0)
            val betaRow = childElement(parent, index = 1)
            val gammaRow = childElement(parent, index = 2)
            val alphaA1 = childElement(childElement(alphaRow, index = 0), index = 0)
            val betaB2 = childElement(childElement(betaRow, index = 0), index = 1)
            val gammaG3 = childElement(childElement(gammaRow, index = 0), index = 2)

            current = nestedRows(nextRows)
            app.render()

            assertEquals(nestedRowsHtml(nextRows), app.innerHtml())
            assertSame(gammaRow, childElement(parent, index = 0))
            assertSame(alphaRow, childElement(parent, index = 1))
            assertSame(betaRow, childElement(parent, index = 2))
            assertSame(gammaG3, childElement(childElement(gammaRow, index = 0), index = 0))
            assertSame(alphaA1, childElement(childElement(alphaRow, index = 0), index = 2))
            assertSame(betaB2, childElement(childElement(betaRow, index = 0), index = 0))
        } finally {
            app.dispose()
        }
    }

    @Test
    fun disjointKeyReplacementIsFollowedByPartialOverlapKeyedReuse() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        var current: Node = keyedTextList(
            "alpha" to "Alpha",
            "beta" to "Beta",
            "gamma" to "Gamma",
        )
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            emit(current)
        }

        try {
            current = keyedTextList(
                "xray" to "Xray",
                "yankee" to "Yankee",
                "zulu" to "Zulu",
            )
            app.render()

            assertEquals(
                keyedTextListHtml(
                    "xray" to "Xray",
                    "yankee" to "Yankee",
                    "zulu" to "Zulu",
                ),
                app.innerHtml(),
            )
            val parent = firstElement(root)
            val yankeeRow = childElement(parent, index = 1)

            current = keyedTextList(
                "yankee" to "Yankee",
                "beta" to "Beta",
                "alpha" to "Alpha",
            )
            app.render()

            assertEquals(
                keyedTextListHtml(
                    "yankee" to "Yankee",
                    "beta" to "Beta",
                    "alpha" to "Alpha",
                ),
                app.innerHtml(),
            )
            assertSame(yankeeRow, childElement(parent, index = 0))
        } finally {
            app.dispose()
        }
    }

    @Test
    fun keyedReconciliationWorksBeyondOldScratchDepthCap() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        var current: Node = deepKeyedTree(level = 0, maxLevel = 5, reversed = false)
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            emit(current)
        }

        try {
            current = deepKeyedTree(level = 0, maxLevel = 5, reversed = true)
            app.render()

            assertEquals(deepKeyedTreeHtml(level = 0, maxLevel = 5, reversed = true), app.innerHtml())
        } finally {
            app.dispose()
        }
    }
}

private data class NestedRow(
    val key: String,
    val innerKeys: List<String>,
)

private fun nestedRows(rows: List<NestedRow>): HostNode =
    HostNode(
        tag = "section",
        children = rows.map { row ->
            HostNode(
                tag = "div",
                key = row.key,
                children = listOf(
                    HostNode(
                        tag = "section",
                        children = row.innerKeys.map { innerKey ->
                            HostNode(
                                tag = "span",
                                key = innerKey,
                                children = listOf(TextNode(innerKey, semantics = null)),
                            )
                        },
                    ),
                ),
            )
        },
    )

private fun nestedRowsHtml(rows: List<NestedRow>): String =
    "<section>${rows.joinToString(separator = "") { row ->
        """<div data-kinetica-key="${row.key}"><section>""" +
            row.innerKeys.joinToString(separator = "") { innerKey ->
                """<span data-kinetica-key="$innerKey">$innerKey</span>"""
            } +
            "</section></div>"
    }}</section>"

private fun keyedTextList(vararg rows: Pair<String, String>): HostNode =
    HostNode(
        tag = "section",
        children = rows.map { (key, label) ->
            HostNode(
                tag = "div",
                key = key,
                children = listOf(TextNode(label, semantics = null)),
            )
        },
    )

private fun keyedTextListHtml(vararg rows: Pair<String, String>): String =
    "<section>${rows.joinToString(separator = "") { (key, label) ->
        """<div data-kinetica-key="$key">$label</div>"""
    }}</section>"

private fun deepKeyedTree(level: Int, maxLevel: Int, reversed: Boolean): HostNode =
    HostNode(
        tag = "section",
        children = deepKeyedChildren(level, maxLevel, reversed),
    )

private fun deepKeyedChildren(level: Int, maxLevel: Int, reversed: Boolean): List<Node> {
    val main = HostNode(
        tag = "div",
        key = "level-$level-main",
        children = if (level == maxLevel) {
            listOf(TextNode("main-$level", semantics = null))
        } else {
            listOf(deepKeyedTree(level + 1, maxLevel, reversed))
        },
    )
    val side = HostNode(
        tag = "div",
        key = "level-$level-side",
        children = listOf(TextNode("side-$level", semantics = null)),
    )
    return if (reversed) listOf(side, main) else listOf(main, side)
}

private fun deepKeyedTreeHtml(level: Int, maxLevel: Int, reversed: Boolean): String =
    "<section>${deepKeyedChildrenHtml(level, maxLevel, reversed)}</section>"

private fun deepKeyedChildrenHtml(level: Int, maxLevel: Int, reversed: Boolean): String {
    val mainChildren = if (level == maxLevel) {
        "main-$level"
    } else {
        deepKeyedTreeHtml(level + 1, maxLevel, reversed)
    }
    val main = """<div data-kinetica-key="level-$level-main">$mainChildren</div>"""
    val side = """<div data-kinetica-key="level-$level-side">side-$level</div>"""
    return if (reversed) side + main else main + side
}

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun childElement(parent: Element, index: Int): Element =
    parent.childNodes.item(index)?.unsafeCast<Element>()
        ?: error("Expected child element at index $index.")
