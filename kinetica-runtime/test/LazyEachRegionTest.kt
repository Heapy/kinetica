package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class LazyEachRegionProbe(
    val items: LazyItems<String> = lazyItems(listOf("a", "b"), estimatedSize = 2),
)

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
    /**
     * C6a: `lazyEachRegion` must record a `ChildRegion` for its emitted row span; red while
     * `ComponentScope.lazyEachRegion` renders rows without appending to the active region frame.
     */
    @Test
    fun lazyEachInsideHostRecordsChildRegionSpan() {
        val section = renderLazyEachSection()

        assertEquals(3, section.children.size)
        assertTrue(
            section.regions.isNotEmpty(),
            "Expected section.regions to contain the lazyEach row span, but regions=${section.regions} in $section",
        )
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
