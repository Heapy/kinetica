package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class LazyEachRegionProbe(
    val items: LazyItems<String> = lazyItems(listOf("a", "b"), estimatedSize = 2),
)

private class LazyLoopProbe(
    var batches: List<List<String>> = emptyList(),
    val retain: RetainPolicy = RetainPolicy.Keyed,
    var window: LazyListState = LazyListState(),
) {
    val inits = mutableListOf<String>()
}

@UiComponent(skippable = false)
private fun ComponentScope.WindowedLazyApp(probe: LazyLoopProbe) {
    host("section") {
        lazyEach(
            lazyItems(probe.batches[0], estimatedSize = probe.batches[0].size),
            key = { item -> item },
            retain = probe.retain,
            state = probe.window,
        ) { item ->
            var count by state {
                probe.inits += item
                0
            }
            host("div", key = item) {
                text("$item:$count", semantics = null)
            }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.LazyLoopApp(probe: LazyLoopProbe) {
    host("section") {
        for ((index, batch) in probe.batches.withIndex()) {
            lazyEach(
                lazyItems(batch, estimatedSize = batch.size),
                key = { item -> item },
                retain = probe.retain,
            ) { item ->
                var count by state {
                    probe.inits += "$index:$item"
                    0
                }
                host("div", key = "$index:$item") {
                    text("$index:$item:$count", semantics = null)
                    button(onClick = { count += 1 }, semantics = null) {
                        text("+", semantics = null)
                    }
                }
            }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.LazyEachRegionApp(probe: LazyEachRegionProbe) {
    host("section") {
        text("h", semantics = null)
        lazyEach(probe.items, key = { item -> item }) { item ->
            host("div", key = item) {
                text(item, semantics = null)
            }
        }
    }
}

class LazyEachRegionTest {
    @Test
    fun lazyEachInsideHostRecordsChildRegionSpan() {
        val section = renderLazyEachSection()

        assertEquals(3, section.children.size)
        assertTrue(
            section.regions.isNotEmpty(),
            "Expected section.regions to contain the lazyEach row span, but regions=${section.regions} in $section",
        )
    }

    @Test
    fun lazyEachInvocationsSharingUserKeysKeepIndependentRowState() {
        // F7 hazard shape for lazyEach (flagged at Task 9, closed at Task 20): two loop
        // invocations share one static ordinal, so rows with the same user key aliased
        // one frame — the second invocation read the first invocation's cells.
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = LazyLoopProbe(batches = listOf(listOf("x", "y"), listOf("x", "y")))

        fun render(): Node = runtime.render(scope) { LazyLoopApp(probe) }.tree

        var tree = render()
        assertEquals(4, probe.inits.size, "each invocation must initialize its own rows: ${probe.inits}")
        assertEquals(listOf("0:x:0", "0:y:0", "1:x:0", "1:y:0"), tree.rowTexts())

        runtime.dispatch(tree.rows()[0].findClickEventId())
        tree = render()
        assertEquals(listOf("0:x:1", "0:y:0", "1:x:0", "1:y:0"), tree.rowTexts())
        assertEquals(4, probe.inits.size)
    }

    @Test
    fun lazyEachVisibleOnlyInLoopKeepsSiblingInvocationRows() {
        // Pre-fix, every invocation's VisibleOnly sweep disposed the SIBLING
        // invocation's rows (their keys are never in its own visible set), so row state
        // re-initialized on every render.
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = LazyLoopProbe(
            batches = listOf(listOf("a", "b"), listOf("c", "d")),
            retain = RetainPolicy.VisibleOnly,
        )

        fun render(): Node = runtime.render(scope) { LazyLoopApp(probe) }.tree

        render()
        assertEquals(4, probe.inits.size)

        render()
        val tree = render()
        assertEquals(4, probe.inits.size, "sibling invocations must not evict each other: ${probe.inits}")
        assertEquals(listOf("0:a:0", "0:b:0", "1:c:0", "1:d:0"), tree.rowTexts())
    }

    @Test
    fun lazyEachPersistentSlotsInLoopKeepsSiblingInvocationRows() {
        // Same shape as the VisibleOnly probe: the PersistentSlots sweep stripped the
        // sibling invocation's frames, resetting their non-persistent state per render.
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = LazyLoopProbe(
            batches = listOf(listOf("a", "b"), listOf("c", "d")),
            retain = RetainPolicy.PersistentSlots,
        )

        fun render(): Node = runtime.render(scope) { LazyLoopApp(probe) }.tree

        render()
        assertEquals(4, probe.inits.size)

        render()
        val tree = render()
        assertEquals(4, probe.inits.size, "sibling invocations must not strip each other: ${probe.inits}")
        assertEquals(listOf("0:a:0", "0:b:0", "1:c:0", "1:d:0"), tree.rowTexts())
    }

    @Test
    fun lazyEachVisibleOnlySingleInvocationStillDisposesHiddenRows() {
        // Pass 0 keeps bare row keys, so the single-invocation VisibleOnly contract is
        // unchanged: rows scrolled out of the window are still disposed by their own
        // call's sweep.
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = LazyLoopProbe(
            batches = listOf(listOf("a", "b", "c")),
            retain = RetainPolicy.VisibleOnly,
        )

        fun render(): Node = runtime.render(scope) { WindowedLazyApp(probe) }.tree

        render()
        assertEquals(3, probe.inits.size)

        probe.window = LazyListState(firstVisibleIndex = 1, visibleCount = 2)
        render()
        assertEquals(3, probe.inits.size)

        probe.window = LazyListState()
        val tree = render()
        assertEquals(4, probe.inits.size, "the hidden row must have been disposed and re-initialized: ${probe.inits}")
        assertEquals(listOf("a:0", "b:0", "c:0"), tree.rowTexts())
    }

    private fun Node.rows(): List<HostNode> =
        (this as HostNode).children.filterIsInstance<HostNode>()

    private fun Node.rowTexts(): List<String> =
        rows().map { row -> row.findText().value }

    private fun HostNode.findClickEventId(): String {
        fun visit(node: Node): String? = when (node) {
            is HostNode -> node.props["event:onClick"] ?: node.children.firstNotNullOfOrNull(::visit)
            is FragmentNode -> node.children.firstNotNullOfOrNull(::visit)
            else -> null
        }
        return visit(this) ?: error("No click event found under row $key.")
    }

    private fun HostNode.findText(): TextNode {
        fun visit(node: Node): TextNode? = when (node) {
            is TextNode -> node
            is HostNode -> node.children.firstNotNullOfOrNull(::visit)
            is FragmentNode -> node.children.firstNotNullOfOrNull(::visit)
            else -> null
        }
        return visit(this) ?: error("No text found under row $key.")
    }

    private fun renderLazyEachSection(): HostNode {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val tree = runtime.render(scope) {
            LazyEachRegionApp(LazyEachRegionProbe())
        }.tree
        return findHost(tree, "section") ?: error("Expected section host in $tree.")
    }

    private fun findHost(node: Node, tag: String): HostNode? =
        when (node) {
            is HostNode -> if (node.tag == tag) node else node.children.firstNotNullOfOrNull { child ->
                findHost(child, tag)
            }
            is FragmentNode -> node.children.firstNotNullOfOrNull { child -> findHost(child, tag) }
            else -> null
        }
}
