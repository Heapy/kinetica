package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals

private class RegionOrdinalCollisionProbe(
    var itemsA: List<String> = listOf("a1", "a2"),
    var itemsB: List<String> = listOf("b1", "b2"),
)

@UiComponent(skippable = false)
private fun ComponentScope.RegionOrdinalRowsA(probe: RegionOrdinalCollisionProbe) {
    each(probe.itemsA, key = { item -> item }) { item ->
        host("div", key = item) {
            text(item, semantics = null)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.RegionOrdinalRowsB(probe: RegionOrdinalCollisionProbe) {
    each(probe.itemsB, key = { item -> item }) { item ->
        host("div", key = item) {
            text(item, semantics = null)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.RegionOrdinalCollisionApp(probe: RegionOrdinalCollisionProbe) {
    host("section") {
        RegionOrdinalRowsA(probe)
        RegionOrdinalRowsB(probe)
    }
}

class RegionOrdinalCollisionTest {
    private fun sectionFor(probe: RegionOrdinalCollisionProbe): HostNode {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val tree = runtime.render(scope) {
            RegionOrdinalCollisionApp(probe)
        }.tree
        return findSection(tree) ?: error("Expected a section host in $tree.")
    }

    private fun findSection(node: Node): HostNode? =
        when (node) {
            is HostNode -> if (node.tag == "section") node else node.children.firstNotNullOfOrNull(::findSection)
            is FragmentNode -> node.children.firstNotNullOfOrNull(::findSection)
            else -> null
        }

    /**
     * C1: two non-skippable child components leak component-local `each` ordinal 0 into
     * one parent host; this is red while `section.regions.map { it.ordinal } == [0, 0]`.
     */
    @Test
    fun flatEachRegionsFromComposedNonSkippableChildrenHaveUniqueOrdinals() {
        val section = sectionFor(RegionOrdinalCollisionProbe())
        val ordinals = section.regions.map { region -> region.ordinal }

        assertEquals(
            section.regions.size,
            ordinals.toSet().size,
            "Sibling ChildRegion ordinals on a single host must be unique; regions=$section",
        )
    }
}
