package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class ButtonRegionProbe(
    val icons: List<String> = listOf("save", "open"),
)

@UiComponent(skippable = false)
private fun ComponentScope.ButtonRegionApp(probe: ButtonRegionProbe) {
    button(semantics = null) {
        text("x", semantics = null)
        each(probe.icons, key = { icon -> icon }) { icon ->
            host("div", key = icon) {
                text(icon, semantics = null)
            }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.HostRegionContrastApp(probe: ButtonRegionProbe) {
    host("section") {
        text("x", semantics = null)
        each(probe.icons, key = { icon -> icon }) { icon ->
            host("div", key = icon) {
                text(icon, semantics = null)
            }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.ColumnRegionContrastApp(probe: ButtonRegionProbe) {
    column {
        text("x", semantics = null)
        each(probe.icons, key = { icon -> icon }) { icon ->
            host("div", key = icon) {
                text(icon, semantics = null)
            }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.RowRegionContrastApp(probe: ButtonRegionProbe) {
    row {
        text("x", semantics = null)
        each(probe.icons, key = { icon -> icon }) { icon ->
            host("div", key = icon) {
                text(icon, semantics = null)
            }
        }
    }
}

class ButtonRegionThreadingTest {
    /**
     * C6b: `button` must thread `lastCollectedRegions` into its emitted `HostNode`; red while
     * the same direct `each` records regions for `host`/`column`/`row` but `button.regions` is empty.
     */
    @Test
    fun buttonKeepsEachChildRegionsLikeOtherHostContainers() {
        val probe = ButtonRegionProbe()
        val host = renderSingleHost("section") { HostRegionContrastApp(probe) }
        val column = renderSingleHost("column") { ColumnRegionContrastApp(probe) }
        val row = renderSingleHost("row") { RowRegionContrastApp(probe) }

        assertTrue(host.regions.isNotEmpty(), "Contrast failed: host(...) did not attach regions in $host")
        assertTrue(column.regions.isNotEmpty(), "Contrast failed: column did not attach regions in $column")
        assertTrue(row.regions.isNotEmpty(), "Contrast failed: row did not attach regions in $row")

        val button = renderSingleHost("button") { ButtonRegionApp(probe) }

        assertEquals(3, button.children.size)
        assertTrue(
            button.regions.isNotEmpty(),
            "Expected button.regions to contain the each row span, but regions=${button.regions} in $button",
        )
    }

    private fun renderSingleHost(tag: String, content: @UiComponent ComponentScope.() -> Unit): HostNode {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val tree = runtime.render(scope, content).tree
        return findHost(tree, tag) ?: error("Expected $tag host in $tree.")
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
