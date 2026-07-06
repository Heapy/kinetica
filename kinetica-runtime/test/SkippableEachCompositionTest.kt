package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Skippable components must COMPOSE with the rest of the render machinery instead of
 * degrading it: skip hits replay captured events/reads exactly like memoized each rows do,
 * unrelated state writes never defeat a skip, and replay-unsafe bodies (effects) are simply
 * never cached.
 *
 * Frame-era port: `skippableNode` is now emitted by the compiler around receiver-style
 * `@UiComponent` functions with stable inputs, so each scenario uses a real component
 * instead of a hand-rolled `skippableNode` call. The old positional-cursor alignment test
 * (siblingPositionalSlotsStayAlignedAcrossSkips) is dropped: slot identity is structural
 * per frame, cursors no longer exist.
 */
private data class SkipItem(val id: Int, val label: String)

private var skipItems: List<SkipItem> = emptyList()
private var skipBadgeRuns = 0

@UiComponent
private fun ComponentScope.SkipBadge(item: SkipItem) {
    skipBadgeRuns++
    host("span") { text(item.label, semantics = null) }
}

@UiComponent(skippable = false)
private fun ComponentScope.SkipBadgeRows() {
    column {
        each(skipItems, key = { it.id }) { item ->
            host("li", key = item.id) { SkipBadge(item) }
        }
    }
}

private var chipRuns = 0
private var chipCounterCell: MutableCell<Int>? = null

@UiComponent
private fun ComponentScope.SkipChip() {
    chipRuns++
    host("span") { text("chip", semantics = null) }
}

@UiComponent(skippable = false)
private fun ComponentScope.SkipChipHost() {
    val counter = state { 0 }
    chipCounterCell = counter
    host("div") {
        text("count ${counter.value}", semantics = null)
        SkipChip()
    }
}

private var clickerClicks = 0
private var clickerTickCell: MutableCell<Int>? = null

@UiComponent
private fun ComponentScope.SkipClicker() {
    button(onClick = event { clickerClicks++ }, semantics = null) {
        text("hit me", semantics = null)
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SkipClickerHost() {
    val tick = state { 0 }
    clickerTickCell = tick
    host("div") {
        text("tick ${tick.value}", semantics = null)
        SkipClicker()
    }
}

private var skipMode = "cached"
private val skipModeClicks = mutableListOf<String>()
private var cachedClickerRuns = 0

@UiComponent
private fun ComponentScope.SkipCachedClicker() {
    cachedClickerRuns++
    button(onClick = { skipModeClicks += "cached" }, semantics = null) {
        text("cached", semantics = null)
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SkipModalHost() {
    host("div") {
        when (skipMode) {
            "cached" -> SkipCachedClicker()
            "fresh-event" -> button(onClick = { skipModeClicks += "fresh" }, semantics = null) {
                text("fresh", semantics = null)
            }
            else -> text("empty", semantics = null)
        }
    }
}

private var effectfulRuns = 0

@UiComponent
private fun ComponentScope.SkipEffectfulChip() {
    effectfulRuns++
    launchEffect { }
    host("span") { text("effectful", semantics = null) }
}

@UiComponent(skippable = false)
private fun ComponentScope.SkipEffectfulHost() {
    host("div") {
        SkipEffectfulChip()
    }
}

class SkippableEachCompositionTest {
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
        skipItems = listOf(SkipItem(1, "one"), SkipItem(2, "two"), SkipItem(3, "three"))
        skipBadgeRuns = 0

        fun render(): Node = runtime.render(scope) { SkipBadgeRows() }.tree

        val first = render().hostChildren()
        val second = render().hostChildren()

        assertEquals(3, skipBadgeRuns, "each row's badge body runs once; skips must not re-run it")
        assertEquals(3, second.size)
        // A skippable hit inside a row must not mark the enclosing row capture unsafe:
        // the rows stay reference-equal (memoized).
        first.zip(second).forEach { (before, after) -> assertSame(before, after) }
    }

    @Test
    fun stateWritesElsewhereDoNotDefeatSkips() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        chipRuns = 0
        chipCounterCell = null

        fun render(): Node = runtime.render(scope) { SkipChipHost() }.tree

        render()
        assertEquals(1, chipRuns)

        // The chip reads no cells, so a write to an unrelated cell must not re-run its body
        // (the old global stateWriteVersion guard invalidated EVERY skippable cache).
        chipCounterCell!!.value = 5
        val second = render()

        assertEquals(1, chipRuns, "unrelated state write must not defeat the skip")
        assertEquals(listOf("count 5", "chip"), second.collectTexts())
    }

    @Test
    fun skippedComponentEventHandlerSurvivesCommits() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        clickerClicks = 0
        clickerTickCell = null

        fun render(): Node = runtime.render(scope) { SkipClickerHost() }.tree

        render()
        clickerTickCell!!.value = 1
        // This render SKIPS the clicker; without event re-touching, commit would evict the
        // handler and the dispatch below would be lost.
        val second = render()

        runtime.dispatch(second.findClickEventId(), Unit)
        assertEquals(1, clickerClicks, "handler inside a skipped component must survive the commit sweep")

        clickerTickCell!!.value = 2
        val third = render()
        runtime.dispatch(third.findClickEventId(), Unit)
        assertEquals(2, clickerClicks)
    }

    @Test
    fun skippedComponentRebuildsAfterEventEvictionAndKeyReuse() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        skipMode = "cached"
        skipModeClicks.clear()
        cachedClickerRuns = 0

        fun render(): Node = runtime.render(scope) { SkipModalHost() }.tree

        render()
        skipMode = "empty"
        render()
        skipMode = "fresh-event"
        render()
        skipMode = "cached"
        val rebuilt = render()

        assertEquals(2, cachedClickerRuns)
        runtime.dispatch(rebuilt.findClickEventId(), Unit)
        assertEquals(listOf("cached"), skipModeClicks)
    }

    @Test
    fun effectfulSkippableIsNeverCached() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        effectfulRuns = 0

        fun render(): Node = runtime.render(scope) { SkipEffectfulHost() }.tree

        render()
        render()

        assertEquals(2, effectfulRuns, "a body scheduling effects is replay-unsafe and must re-run every render")
    }
}
