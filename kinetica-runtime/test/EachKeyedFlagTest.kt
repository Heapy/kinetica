package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * NodeFlags.CHILDREN_KEYED certification: `each` proves "every child is exactly one HostNode
 * keyed by its unique row key" and `host` stamps the flag — renderers may then run keyed
 * reconciliation without re-scanning children. Anything the proof doesn't cover must stay
 * unflagged (a wrong flag would mis-reconcile), so the poisoning cases matter most.
 *
 * Frame-era port: `each` may only be called inside a `@UiComponent` function, so every
 * scenario lives in a private top-level component below.
 */
private data class FlagItem(val id: Int, val label: String)

private val flagItems = listOf(FlagItem(1, "one"), FlagItem(2, "two"), FlagItem(3, "three"))

@UiComponent(skippable = false)
private fun ComponentScope.KeyedRowsCertified() {
    host("tbody") {
        each(flagItems, key = { it.id }) { item ->
            host("tr", key = item.id) { text(item.label, semantics = null) }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.KeyedRowsNonMemoized() {
    host("tbody") {
        each(flagItems, key = { it.id }, memoize = false) { item ->
            host("tr", key = item.id) { text(item.label, semantics = null) }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.KeyedRowsHeaderBefore() {
    host("tbody") {
        host("tr", key = "header")
        each(flagItems, key = { it.id }) { item ->
            host("tr", key = item.id) { text(item.label, semantics = null) }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.KeyedRowsFooterAfter() {
    host("tbody") {
        each(flagItems, key = { it.id }) { item ->
            host("tr", key = item.id) { text(item.label, semantics = null) }
        }
        host("tr", key = "footer")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.KeyedRowsTwoNodesPerRow() {
    host("tbody") {
        each(flagItems, key = { it.id }) { item ->
            host("tr", key = item.id)
            host("tr", key = "${item.id}-detail")
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.KeyedRowsStaticHostKey() {
    // host keys not derived from the row key could collide across rows — not certifiable
    host("tbody") {
        each(flagItems, key = { it.id }) { item ->
            host("tr", key = "static") { text(item.label, semantics = null) }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.KeyedRowsUnkeyedHost() {
    host("tbody") {
        each(flagItems, key = { it.id }) { item ->
            host("tr") { text(item.label, semantics = null) }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.KeyedRowsEmpty() {
    host("tbody") {
        each(emptyList<FlagItem>(), key = { it.id }) { item ->
            host("tr", key = item.id)
        }
    }
}

class EachKeyedFlagTest {
    private fun tbodyFlags(content: @UiComponent ComponentScope.() -> Unit): Int {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val tree = runtime.render(scope, content).tree
        fun find(node: Node): HostNode? = when (node) {
            is HostNode -> if (node.tag == "tbody") node else node.children.firstNotNullOfOrNull(::find)
            is FragmentNode -> node.children.firstNotNullOfOrNull(::find)
            else -> null
        }
        return find(tree)?.flags ?: error("no tbody in $tree")
    }

    @Test
    fun keyedEachRowsCertifyTheParentHost() {
        assertEquals(NodeFlags.CHILDREN_KEYED, tbodyFlags { KeyedRowsCertified() })
    }

    @Test
    fun nonMemoizedEachAlsoCertifies() {
        assertEquals(NodeFlags.CHILDREN_KEYED, tbodyFlags { KeyedRowsNonMemoized() })
    }

    @Test
    fun memoizedSecondRenderKeepsTheFlag() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        fun render(): HostNode = runtime.render(scope) {
            KeyedRowsCertified()
        }.tree as HostNode
        render()
        // second render reuses cached rows; certification must come from the row cache
        assertEquals(NodeFlags.CHILDREN_KEYED, render().flags)
    }

    @Test
    fun emissionBeforeEachPoisonsTheFlag() {
        assertEquals(0, tbodyFlags { KeyedRowsHeaderBefore() })
    }

    @Test
    fun emissionAfterEachPoisonsTheFlag() {
        assertEquals(0, tbodyFlags { KeyedRowsFooterAfter() })
    }

    @Test
    fun rowEmittingTwoNodesPoisonsTheFlag() {
        assertEquals(0, tbodyFlags { KeyedRowsTwoNodesPerRow() })
    }

    @Test
    fun rowKeyMismatchPoisonsTheFlag() {
        assertEquals(0, tbodyFlags { KeyedRowsStaticHostKey() })
    }

    @Test
    fun unkeyedRowHostPoisonsTheFlag() {
        assertEquals(0, tbodyFlags { KeyedRowsUnkeyedHost() })
    }

    @Test
    fun emptyEachDoesNotCertify() {
        assertEquals(0, tbodyFlags { KeyedRowsEmpty() })
    }

    @Test
    fun plainSingleTextChildCertifiesHostColumnAndRow() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun renderFlags(body: ComponentScope.() -> Unit): Int =
            (runtime.render(scope, body).tree as HostNode).flags

        assertEquals(NodeFlags.CHILDREN_SINGLE_TEXT, renderFlags { host("p") { text("plain", semantics = null) } })
        assertEquals(NodeFlags.CHILDREN_SINGLE_TEXT, renderFlags { column { text("plain", semantics = null) } })
        assertEquals(NodeFlags.CHILDREN_SINGLE_TEXT, renderFlags { row { text("plain", semantics = null) } })
    }

    @Test
    fun singleTextFlagRejectsWrappedOrNonSingleTextChildren() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun renderFlags(body: ComponentScope.() -> Unit): Int =
            (runtime.render(scope, body).tree as HostNode).flags

        assertEquals(NodeFlags.CHILDREN_SINGLE_TEXT, renderFlags { host("p") { text("default semantics") } })
        assertEquals(0, renderFlags { host("p") { text("image", semantics = Semantics(role = Role.Image, label = "Image")) } })
        assertEquals(0, renderFlags { host("p") { text("deleted", strikethrough = true, semantics = null) } })
        assertEquals(0, renderFlags { host("p") { text("one", semantics = null); text("two", semantics = null) } })
    }

    @Test
    fun asLeavingStripsSingleTextFlagButKeepsKeyedFlag() {
        val singleText = HostNode(
            tag = "div",
            children = listOf(TextNode("Item", semantics = null)),
            flags = NodeFlags.CHILDREN_SINGLE_TEXT,
        )
        val keyed = HostNode(
            tag = "div",
            children = listOf(HostNode("span", key = "item")),
            flags = NodeFlags.CHILDREN_KEYED,
        )

        val leavingSingleText = assertIs<HostNode>(singleText.asLeaving())
        val leavingKeyed = assertIs<HostNode>(keyed.asLeaving())

        assertEquals(0, leavingSingleText.flags and NodeFlags.CHILDREN_SINGLE_TEXT)
        assertEquals(NodeFlags.CHILDREN_KEYED, leavingKeyed.flags and NodeFlags.CHILDREN_KEYED)
    }
}
