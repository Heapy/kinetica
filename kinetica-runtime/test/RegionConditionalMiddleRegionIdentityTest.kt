package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

private class RegionConditionalMiddleProbe(
    var showMiddle: Boolean = true,
    val itemsA: List<String> = listOf("a1", "a2"),
    val itemsB: List<String> = listOf("b1", "b2"),
    val itemsC: List<String> = listOf("c1", "c2"),
)

@UiComponent(skippable = false)
private fun ComponentScope.RegionConditionalMiddleApp(probe: RegionConditionalMiddleProbe) {
    host("section") {
        each(probe.itemsA, key = { item -> item }) { item ->
            host("div", key = item) {
                text(item, semantics = null)
            }
        }
        if (probe.showMiddle) {
            each(probe.itemsB, key = { item -> item }) { item ->
                host("div", key = item) {
                    text(item, semantics = null)
                }
            }
        }
        each(probe.itemsC, key = { item -> item }) { item ->
            host("div", key = item) {
                text(item, semantics = null)
            }
        }
    }
}

class RegionConditionalMiddleRegionIdentityTest {
    @Test
    fun omittingMiddleRegionKeepsTrailingRegionIdentityStable() {
        val probe = RegionConditionalMiddleProbe()
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun renderSection(): HostNode {
            val tree = runtime.render(scope) {
                RegionConditionalMiddleApp(probe)
            }.tree
            return findSection(tree) ?: error("Expected section in $tree.")
        }

        val first = renderSection()
        val firstCOrdinal = first.regionOrdinalForRows(listOf("c1", "c2"))
        val firstCRows = first.rowHosts("c")

        probe.showMiddle = false
        val second = renderSection()
        val secondCOrdinal = second.regionOrdinalForRows(listOf("c1", "c2"))
        val secondCRows = second.rowHosts("c")

        assertEquals(
            firstCOrdinal,
            secondCOrdinal,
            "Trailing each region must keep its identity when a middle sibling region is omitted.",
        )
        assertEquals(listOf("a1", "a2", "c1", "c2"), second.rowKeys())
        firstCRows.zip(secondCRows).forEach { (old, new) ->
            assertSame(old, new)
        }
    }

    private fun findSection(node: Node): HostNode? =
        when (node) {
            is HostNode -> if (node.tag == "section") node else node.children.firstNotNullOfOrNull(::findSection)
            is FragmentNode -> node.children.firstNotNullOfOrNull(::findSection)
            else -> null
        }

    private fun HostNode.regionOrdinalForRows(keys: List<String>): Int {
        val childKeys = children.filterIsInstance<HostNode>().map { node -> node.key }
        return regions.single { region ->
            childKeys.subList(region.start, region.end) == keys
        }.ordinal
    }

    private fun HostNode.rowHosts(prefix: String): List<HostNode> =
        children.filterIsInstance<HostNode>().filter { node -> node.key?.startsWith(prefix) == true }

    private fun HostNode.rowKeys(): List<String?> =
        children.filterIsInstance<HostNode>().map { node -> node.key }
}
