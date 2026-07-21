package io.heapy.kinetica.render

import io.heapy.kinetica.FragmentNode
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** In-memory widget: an ordered child list plus prop/text state. */
private class TestView(val tag: String) {
    val children = mutableListOf<TestView>()
    val props = mutableMapOf<String, String>()
    var text: String? = null
    var folded: String? = null
    var semantics: Semantics? = null

    /** Render the subtree as a compact string for order assertions. */
    fun dump(): String = when {
        tag == "#text" -> "\"$text\""
        children.isEmpty() -> tag
        else -> "$tag(${children.joinToString(",") { it.dump() }})"
    }
}

private class MockAdapter : HostAdapter<TestView> {
    val ops = mutableListOf<String>()

    override fun createHost(node: HostNode): TestView {
        ops += "create:${node.tag}${node.key?.let { "#$it" }.orEmpty()}"
        return TestView(node.tag).also { view -> view.props.putAll(node.props) }
    }

    override fun createText(node: TextNode): TestView {
        ops += "createText:${node.value}"
        return TestView("#text").also { view -> view.text = node.value }
    }

    override fun setText(view: TestView, node: TextNode) {
        ops += "setText:${node.value}"
        view.text = node.value
    }

    override fun setProp(view: TestView, name: String, value: String, node: HostNode) {
        ops += "setProp:$name=$value"
        view.props[name] = value
    }

    override fun removeProp(view: TestView, name: String, node: HostNode) {
        ops += "removeProp:$name"
        view.props.remove(name)
    }

    override fun applySemantics(view: TestView, semantics: Semantics?, nativeTag: String) {
        view.semantics = semantics
    }

    override fun insert(container: TestView, child: TestView, before: TestView?) {
        ops += "insert:${child.tag}"
        if (before == null) {
            container.children += child
        } else {
            container.children.add(container.children.indexOf(before), child)
        }
    }

    override fun remove(container: TestView, child: TestView) {
        ops += "remove:${child.tag}"
        container.children.remove(child)
    }

    override fun move(container: TestView, child: TestView, before: TestView?) {
        ops += "move:${child.tag}"
        container.children.remove(child)
        if (before == null) {
            container.children += child
        } else {
            container.children.add(container.children.indexOf(before), child)
        }
    }

    override fun foldsChildren(tag: String): Boolean = tag == "button"

    override fun updateFoldedContent(view: TestView, node: HostNode) {
        ops += "fold:${node.tag}"
        view.folded = (node.children.singleOrNull() as? TextNode)?.value
    }

    override fun isControlledTag(tag: String): Boolean = tag == "textInput"

    override fun syncControlledState(view: TestView, node: HostNode) {
        ops += "sync:${node.props["value"].orEmpty()}"
    }

    override fun teardownHost(view: TestView, node: HostNode) {
        ops += "teardown:${node.tag}"
    }

    fun creates(): Int = ops.count { it.startsWith("create") }
    fun moves(): Int = ops.count { it.startsWith("move") }
    fun clear() = ops.clear()
}

private fun row(key: String, text: String = key): HostNode =
    HostNode(tag = "row", key = key, children = listOf(TextNode(text)))

class ReconcilerTest {
    @Test
    fun mountBuildsTheWidgetTreeInOrder() {
        val adapter = MockAdapter()
        val root = TestView("root")
        val reconciler = Reconciler(adapter)

        reconciler.mount(
            HostNode(
                tag = "column",
                children = listOf(TextNode("title"), HostNode(tag = "row")),
            ),
            root,
        )

        assertEquals("root(column(\"title\",row))", root.dump())
    }

    @Test
    fun textUpdatePatchesInPlaceWithoutStructuralOps() {
        val adapter = MockAdapter()
        val root = TestView("root")
        val reconciler = Reconciler(adapter)
        val mounted = reconciler.mount(HostNode("column", children = listOf(TextNode("a"))), root)
        val textView = (root.children.single().children.single())
        adapter.clear()

        reconciler.patch(mounted, HostNode("column", children = listOf(TextNode("b"))), root)

        assertEquals(listOf("setText:b"), adapter.ops)
        assertSame(textView, root.children.single().children.single())
    }

    @Test
    fun keyedReorderMovesInsteadOfRecreating() {
        val adapter = MockAdapter()
        val root = TestView("root")
        val reconciler = Reconciler(adapter)
        val mounted = reconciler.mount(
            HostNode("column", children = listOf(row("a"), row("b"), row("c"))),
            root,
        )
        val column = root.children.single()
        val viewsByText = column.children.associateBy { it.children.single().text }
        adapter.clear()

        reconciler.patch(
            mounted,
            HostNode("column", children = listOf(row("c"), row("a"), row("b"))),
            root,
        )

        assertEquals("column(row(\"c\"),row(\"a\"),row(\"b\"))", column.dump())
        assertEquals(0, adapter.creates(), "reorder must not recreate rows: ${adapter.ops}")
        assertTrue(adapter.moves() >= 1, "reorder needs at least one move: ${adapter.ops}")
        assertSame(viewsByText["a"], column.children[1], "row identity must survive the move")
    }

    @Test
    fun keyedInsertAndRemoveInTheMiddle() {
        val adapter = MockAdapter()
        val root = TestView("root")
        val reconciler = Reconciler(adapter)
        val mounted = reconciler.mount(
            HostNode("column", children = listOf(row("a"), row("b"), row("c"))),
            root,
        )
        val column = root.children.single()
        adapter.clear()

        reconciler.patch(
            mounted,
            HostNode("column", children = listOf(row("a"), row("x"), row("c"))),
            root,
        )

        assertEquals("column(row(\"a\"),row(\"x\"),row(\"c\"))", column.dump())
        assertTrue(adapter.ops.contains("teardown:row"), "removed row must tear down: ${adapter.ops}")
        assertEquals(1, adapter.ops.count { it == "create:row#x" }, adapter.ops.toString())
    }

    @Test
    fun duplicateKeysFallBackToPositionalWithoutCorruption() {
        val adapter = MockAdapter()
        val root = TestView("root")
        val reconciler = Reconciler(adapter)
        val mounted = reconciler.mount(
            HostNode("column", children = listOf(row("a", "1"), row("b", "2"))),
            root,
        )
        val column = root.children.single()
        adapter.clear()

        // "dup" appears twice — keyed reconciliation must refuse and patch positionally.
        reconciler.patch(
            mounted,
            HostNode("column", children = listOf(row("dup", "3"), row("dup", "4"))),
            root,
        )

        assertEquals("column(row(\"3\"),row(\"4\"))", column.dump())
        assertEquals(0, adapter.moves(), "positional path must not move: ${adapter.ops}")
    }

    @Test
    fun tagChangeReplacesTheWidgetInPlace() {
        val adapter = MockAdapter()
        val root = TestView("root")
        val reconciler = Reconciler(adapter)
        val mounted = reconciler.mount(
            HostNode("column", children = listOf(HostNode("row"), TextNode("after"))),
            root,
        )
        val column = root.children.single()
        adapter.clear()

        reconciler.patch(
            mounted,
            HostNode("column", children = listOf(HostNode("checkbox"), TextNode("after"))),
            root,
        )

        assertEquals("column(checkbox,\"after\")", column.dump())
        assertTrue(adapter.ops.contains("create:checkbox"), adapter.ops.toString())
        assertTrue(adapter.ops.contains("remove:row"), adapter.ops.toString())
    }

    @Test
    fun identicalListsProduceNoStructuralOps() {
        val adapter = MockAdapter()
        val root = TestView("root")
        val reconciler = Reconciler(adapter)
        val tree = HostNode("column", children = listOf(row("a"), row("b")))
        val mounted = reconciler.mount(tree, root)
        adapter.clear()

        reconciler.patch(mounted, HostNode("column", children = listOf(row("a"), row("b"))), root)

        assertEquals(emptyList(), adapter.ops.filter { op ->
            op.startsWith("create") || op.startsWith("move") || op.startsWith("remove") || op.startsWith("insert")
        }, adapter.ops.toString())
    }

    @Test
    fun foldedWidgetMountsNoChildViewsAndRefoldsOnPatch() {
        val adapter = MockAdapter()
        val root = TestView("root")
        val reconciler = Reconciler(adapter)
        val mounted = reconciler.mount(
            HostNode("button", children = listOf(TextNode("Click"))),
            root,
        )
        val button = root.children.single()
        assertEquals(0, button.children.size, "folded caption must not mount a child view")
        adapter.clear()

        reconciler.patch(mounted, HostNode("button", children = listOf(TextNode("Again"))), root)

        assertEquals("Again", button.folded)
        assertEquals(listOf("fold:button"), adapter.ops)
    }

    @Test
    fun controlledHostResyncsOnEveryPatchEvenWhenEqual() {
        val adapter = MockAdapter()
        val root = TestView("root")
        val reconciler = Reconciler(adapter)
        val node = HostNode("textInput", props = mapOf("value" to "v"))
        val mounted = reconciler.mount(node, root)
        adapter.clear()

        reconciler.patch(mounted, HostNode("textInput", props = mapOf("value" to "v")), root)

        assertEquals(listOf("sync:v"), adapter.ops)
    }

    @Test
    fun fragmentChildrenFlattenAndKeepOrderAgainstFollowingSiblings() {
        val adapter = MockAdapter()
        val root = TestView("root")
        val reconciler = Reconciler(adapter)
        val mounted = reconciler.mount(
            HostNode(
                tag = "column",
                children = listOf(
                    FragmentNode(children = listOf(TextNode("f1"))),
                    HostNode("row"),
                ),
            ),
            root,
        )
        val column = root.children.single()
        adapter.clear()

        // The fragment grows a second child; it must land BEFORE the trailing row.
        reconciler.patch(
            mounted,
            HostNode(
                tag = "column",
                children = listOf(
                    FragmentNode(children = listOf(TextNode("f1"), TextNode("f2"))),
                    HostNode("row"),
                ),
            ),
            root,
        )

        assertEquals("column(\"f1\",\"f2\",row)", column.dump())
    }

    @Test
    fun unmountTearsDownInnermostFirstAndRemovesOnlyTheRoot() {
        val adapter = MockAdapter()
        val root = TestView("root")
        val reconciler = Reconciler(adapter)
        val mounted = reconciler.mount(
            HostNode("column", children = listOf(HostNode("row", children = listOf(HostNode("checkbox"))))),
            root,
        )
        adapter.clear()

        reconciler.unmount(mounted, root)

        assertEquals(
            listOf("teardown:checkbox", "teardown:row", "teardown:column", "remove:column"),
            adapter.ops,
        )
        assertEquals(0, root.children.size)
    }
}
