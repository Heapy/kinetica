package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * skippableNode must COMPOSE with the rest of the render machinery instead of degrading it:
 * hits replay captured events/cursors/reads exactly like memoized each rows do, the old
 * global state-write guard is gone, and replay-unsafe factories are simply never cached.
 */
class SkippableEachCompositionTest {
    private data class Item(val id: Int, val label: String)

    private fun Node.hostChildren(): List<HostNode> =
        (this as HostNode).children.filterIsInstance<HostNode>()

    private fun Node.findClickEventId(): String {
        fun visit(node: Node): String? = when (node) {
            is HostNode -> node.props["event:onClick"] ?: node.children.firstNotNullOfOrNull(::visit)
            is FragmentNode -> node.children.firstNotNullOfOrNull(::visit)
            else -> null
        }
        return visit(this) ?: error("No click event found under $this.")
    }

    private fun Node.collectTexts(): List<String> {
        val texts = mutableListOf<String>()
        fun visit(node: Node) {
            when (node) {
                is TextNode -> texts += node.value
                is HostNode -> node.children.forEach(::visit)
                is FragmentNode -> node.children.forEach(::visit)
                else -> {}
            }
        }
        visit(this)
        return texts
    }

    @Test
    fun skippableInsideEachRowKeepsRowMemoized() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val items = listOf(Item(1, "one"), Item(2, "two"), Item(3, "three"))
        var factoryRuns = 0

        fun ComponentScope.badge(item: Item): Node =
            skippableNode("badge", listOf(item)) {
                factoryRuns++
                renderNode {
                    host("span") { text(item.label, semantics = null) }
                }
            }

        fun render(): Node = runtime.render(scope) {
            column {
                each(items, key = { it.id }) { item ->
                    host("li", key = item.id) { emit(badge(item)) }
                }
            }
        }.tree

        val first = render().hostChildren()
        val second = render().hostChildren()

        assertEquals(3, factoryRuns, "each row's factory runs once; skips must not re-run it")
        assertEquals(3, second.size)
        // Before the fix, a skippable hit marked the enclosing row capture unsafe and every
        // row rebuilt each render; now the rows stay reference-equal (memoized).
        first.zip(second).forEach { (before, after) -> assertSame(before, after) }
    }

    @Test
    fun stateWritesElsewhereDoNotDefeatSkips() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var counterCell: MutableCell<Int>? = null
        var factoryRuns = 0

        fun render(): Node = runtime.render(scope) {
            val counter = state(key = "counter") { 0 }
            counterCell = counter
            host("div") {
                text("count ${counter.value}", semantics = null)
                emit(
                    skippableNode("chip", listOf("const")) {
                        factoryRuns++
                        renderNode { host("span") { text("chip", semantics = null) } }
                    },
                )
            }
        }.tree

        render()
        assertEquals(1, factoryRuns)

        // The old global stateWriteVersion guard invalidated EVERY skippable cache on any
        // state write; the chip reads no cells, so this write must not re-run its factory.
        counterCell!!.value = 5
        val second = render()

        assertEquals(1, factoryRuns, "unrelated state write must not defeat the skip")
        assertEquals(listOf("count 5", "chip"), second.collectTexts())
    }

    @Test
    fun skippedComponentEventHandlerSurvivesCommits() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var clicks = 0
        var tickCell: MutableCell<Int>? = null

        fun render(): Node = runtime.render(scope) {
            val tick = state(key = "tick") { 0 }
            tickCell = tick
            host("div") {
                text("tick ${tick.value}", semantics = null)
                emit(
                    skippableNode("clicker", listOf("const")) {
                        renderNode {
                            button(onClick = event { clicks++ }, semantics = null) {
                                text("hit me", semantics = null)
                            }
                        }
                    },
                )
            }
        }.tree

        render()
        tickCell!!.value = 1
        // This render SKIPS the clicker; without event re-touching, commit would evict the
        // handler and the dispatch below would be lost.
        val second = render()

        runtime.dispatch(second.findClickEventId(), Unit)
        assertEquals(1, clicks, "handler inside a skipped component must survive the commit sweep")

        tickCell!!.value = 2
        val third = render()
        runtime.dispatch(third.findClickEventId(), Unit)
        assertEquals(2, clicks)
    }

    @Test
    fun siblingPositionalSlotsStayAlignedAcrossSkips() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var siblingCell: MutableCell<String>? = null

        fun render(): Node = runtime.render(scope) {
            host("div") {
                emit(
                    skippableNode("stateful", listOf("const")) {
                        renderNode {
                            // consumes a positional slot and an event key inside the factory
                            val inner = state { "inner" }
                            button(onClick = event { }, semantics = null) {
                                text(inner.value, semantics = null)
                            }
                        }
                    },
                )
                // positional (unkeyed) slot AFTER the skippable: its key depends on the slot
                // cursor, which the skip must advance exactly as if the factory had run
                val sibling = state { "initial" }
                siblingCell = sibling
                text(sibling.value, semantics = null)
            }
        }.tree

        render()
        siblingCell!!.value = "changed"
        val second = render()

        assertEquals(
            listOf("inner", "changed"),
            second.collectTexts(),
            "cursor misalignment would collide the sibling's positional slot with the skipped component's",
        )
    }

    @Test
    fun effectfulSkippableIsNeverCached() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var factoryRuns = 0

        fun render(): Node = runtime.render(scope) {
            host("div") {
                emit(
                    skippableNode("effectful", listOf("const")) {
                        factoryRuns++
                        renderNode {
                            launchEffect { }
                            host("span") { text("effectful", semantics = null) }
                        }
                    },
                )
            }
        }.tree

        render()
        render()

        assertEquals(2, factoryRuns, "a factory scheduling effects is replay-unsafe and must re-run every render")
    }
}
