package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Frame-era port of the each-row memoization suite: every `each` lives in a private
 * top-level `@UiComponent` component (the compiler plugin assigns slot/child ordinals),
 * parameterized through top-level vars and scope-free cells. Semantics preserved from the
 * cursor era, except sibling slot identity is now structural: the old cursor-delta test is
 * replaced by [EachMemoizationTest.rowStateIdentityIsStableWhenSiblingRowsSkip].
 */
private data class MemoItem(val id: Int, val label: String)

private var memoItems: List<MemoItem> = emptyList()

@UiComponent(skippable = false)
private fun ComponentScope.MemoBasicRows() {
    column {
        each(memoItems, key = { it.id }) { item ->
            host("li", key = item.id) {
                text(item.label, semantics = null)
            }
        }
    }
}

private val memoSelectionCells = mutableMapOf<Int, MutableCell<Boolean>>()

@UiComponent(skippable = false)
private fun ComponentScope.MemoSelectableRows() {
    column {
        each(memoItems, key = { it.id }) { item ->
            val selected = state { false }
            memoSelectionCells[item.id] = selected
            host("li", key = item.id) {
                text("${item.label}:${selected.value}", semantics = null)
            }
        }
    }
}

private val memoClicks = mutableListOf<Int>()

@UiComponent(skippable = false)
private fun ComponentScope.MemoClickableRows() {
    column {
        each(memoItems, key = { it.id }) { item ->
            host("li", key = item.id) {
                button(onClick = { memoClicks += item.id }) {
                    text(item.label, semantics = null)
                }
            }
        }
    }
}

private var memoMode = "list"
private val memoModeClicks = mutableListOf<String>()

@UiComponent(skippable = false)
private fun ComponentScope.MemoModalRows() {
    column {
        when (memoMode) {
            "list" -> each(memoItems, key = { it.id }) { item ->
                host("li", key = item.id) {
                    button(onClick = { memoModeClicks += "row" }) {
                        text(item.label, semantics = null)
                    }
                }
            }
            "fresh-event" -> button(onClick = { memoModeClicks += "fresh" }) {
                text("fresh", semantics = null)
            }
            else -> text("empty", semantics = null)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.MemoCounterRows() {
    column {
        each(memoItems, key = { it.id }) { item ->
            // Frame-era invariant: each row's state lives in its own keyed frame, so a
            // sibling row skipping (memoized) can never shift this row's slot identity.
            var count by state { 0 }
            host("li", key = item.id) {
                text("${item.label}:$count", semantics = null)
                button(onClick = { count += 1 }) {
                    text("+", semantics = null)
                }
            }
        }
    }
}

private val memoTheme = context("light")
private var memoCurrentTheme = "light"

@UiComponent(skippable = false)
private fun ComponentScope.MemoThemedRows() {
    provide(memoTheme, memoCurrentTheme) {
        column {
            each(memoItems, key = { it.id }) { item ->
                host("li", key = item.id) {
                    text("${item.label}:${read(memoTheme)}", semantics = null)
                }
            }
        }
    }
}

private val memoExternal = store("hello")

@UiComponent(skippable = false)
private fun ComponentScope.MemoExternalRows() {
    column {
        each(memoItems, key = { it.id }) { item ->
            host("li", key = item.id) {
                text(memoExternal.value, semantics = null)
            }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.MemoOptOutRows() {
    column {
        each(memoItems, key = { it.id }, memoize = false) { item ->
            host("li", key = item.id) {
                text(item.label, semantics = null)
            }
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.MemoEffectRows() {
    column {
        each(memoItems, key = { it.id }) { item ->
            launchEffect { }
            host("li", key = item.id) {
                text(item.label, semantics = null)
            }
        }
    }
}

private var memoGroups: List<Pair<String, List<String>>> = emptyList()

@UiComponent(skippable = false)
private fun ComponentScope.MemoNestedRows() {
    column {
        each(memoGroups, key = { it.first }) { group ->
            host("section", key = group.first) {
                each(group.second, key = { it }) { name ->
                    host("li", key = name) {
                        text(name, semantics = null)
                    }
                }
            }
        }
    }
}

private data class MemoEffectRow(val id: Int, val label: String, val effect: Boolean = false)

private var memoEffectRows: List<MemoEffectRow> = emptyList()
private val memoEffectRowClicks = mutableListOf<Int>()

@UiComponent(skippable = false)
private fun ComponentScope.MemoConditionalEffectRows() {
    column {
        each(memoEffectRows, key = { it.id }) { item ->
            if (item.effect) {
                launchEffect { }
            }
            host("li", key = item.id) {
                button(onClick = { memoEffectRowClicks += item.id }) {
                    text(item.label, semantics = null)
                }
            }
        }
    }
}

class EachMemoizationTest {
    private fun Node.rows(): List<HostNode> =
        (this as HostNode).children.filterIsInstance<HostNode>()

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

    @Test
    fun unchangedRowsEmitReferenceEqualNodes() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        memoItems = listOf(MemoItem(1, "one"), MemoItem(2, "two"), MemoItem(3, "three"))

        fun render(): Node = runtime.render(scope) { MemoBasicRows() }.tree

        val first = render().rows()
        val second = render().rows()

        assertEquals(3, second.size)
        first.zip(second).forEach { (before, after) -> assertSame(before, after) }
    }

    @Test
    fun changedItemRebuildsOnlyItsRow() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        memoItems = listOf(MemoItem(1, "one"), MemoItem(2, "two"), MemoItem(3, "three"))

        fun render(): List<HostNode> = runtime.render(scope) { MemoBasicRows() }.tree.rows()

        val first = render()
        memoItems = listOf(memoItems[0], MemoItem(2, "TWO"), memoItems[2])
        val second = render()

        assertSame(first[0], second[0])
        assertNotSame(first[1], second[1])
        assertSame(first[2], second[2])
        assertEquals("TWO", second[1].findText().value)
    }

    @Test
    fun reorderedRowsKeepReferenceEqualNodes() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val one = MemoItem(1, "one")
        val two = MemoItem(2, "two")
        val three = MemoItem(3, "three")
        memoItems = listOf(one, two, three)

        fun render(): List<HostNode> = runtime.render(scope) { MemoBasicRows() }.tree.rows()

        val first = render()
        memoItems = listOf(three, one, two)
        val second = render()

        assertSame(first[2], second[0])
        assertSame(first[0], second[1])
        assertSame(first[1], second[2])
    }

    @Test
    fun cellReadInsideRowInvalidatesOnlyReadingRow() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        memoItems = listOf(MemoItem(1, "one"), MemoItem(2, "two"))
        memoSelectionCells.clear()

        fun render(): List<HostNode> = runtime.render(scope) { MemoSelectableRows() }.tree.rows()

        val first = render()
        memoSelectionCells.getValue(2).value = true
        assertTrue(runtime.hasPendingInvalidation)
        val second = render()

        assertSame(first[0], second[0])
        assertNotSame(first[1], second[1])
        assertEquals("two:true", second[1].findText().value)
    }

    @Test
    fun eventsOnMemoizedRowsSurviveEviction() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        memoItems = listOf(MemoItem(1, "one"), MemoItem(2, "two"))
        memoClicks.clear()

        fun render(): List<HostNode> = runtime.render(scope) { MemoClickableRows() }.tree.rows()

        val eventIdsAfterFirst = render().map { it.findClickEventId() }
        val second = render()
        val third = render()

        assertEquals(eventIdsAfterFirst, third.map { it.findClickEventId() })
        assertSame(second[1], third[1])
        runtime.dispatch(third[1].findClickEventId())
        assertEquals(listOf(2), memoClicks)
    }

    @Test
    fun rowEventCacheRebuildsAfterEventEvictionAndKeyReuse() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        memoItems = listOf(MemoItem(1, "one"))
        memoModeClicks.clear()
        memoMode = "list"

        fun render(): HostNode = runtime.render(scope) { MemoModalRows() }.tree as HostNode

        val firstRow = render().children.filterIsInstance<HostNode>().single()
        memoMode = "empty"
        render()
        memoMode = "fresh-event"
        render()
        memoMode = "list"
        val rebuiltRow = render().children.filterIsInstance<HostNode>().single()

        assertNotSame(firstRow, rebuiltRow)
        runtime.dispatch(rebuiltRow.findClickEventId())
        assertEquals(listOf("row"), memoModeClicks)
    }

    @Test
    fun removedRowEvictsCacheAndReaddedRowRebuilds() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val one = MemoItem(1, "one")
        val two = MemoItem(2, "two")
        memoItems = listOf(one, two)
        memoClicks.clear()

        fun render(): List<HostNode> = runtime.render(scope) { MemoClickableRows() }.tree.rows()

        val first = render()
        memoItems = listOf(one)
        val second = render()
        assertEquals(1, second.size)

        memoItems = listOf(one, two)
        val third = render()

        assertSame(first[0], third[0])
        assertNotSame(first[1], third[1])
        runtime.dispatch(third[1].findClickEventId())
        assertEquals(listOf(2), memoClicks)
    }

    @Test
    fun rowStateIdentityIsStableWhenSiblingRowsSkip() {
        // Frame-era replacement for the cursor-delta test: sibling state identity no longer
        // depends on skipped rows reserving cursor positions — each row owns a keyed frame —
        // but the observable contract stays: a memoized sibling must not disturb this row's
        // state across skips and rebuilds.
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        memoItems = listOf(MemoItem(1, "one"), MemoItem(2, "two"))

        fun render(): List<HostNode> = runtime.render(scope) { MemoCounterRows() }.tree.rows()

        val first = render()
        // Row 1 stays memoized while row 2's counter is bumped and its item relabeled.
        runtime.dispatch(first[1].findClickEventId())
        val second = render()
        assertEquals("two:1", second[1].findText().value)
        assertSame(first[0], second[0])

        memoItems = listOf(memoItems[0], MemoItem(2, "TWO"))
        val third = render()
        assertSame(first[0], third[0])
        assertEquals("TWO:1", third[1].findText().value)
    }

    @Test
    fun contextChangeInvalidatesRowCache() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        memoItems = listOf(MemoItem(1, "one"))
        memoCurrentTheme = "light"

        fun render(): List<HostNode> = runtime.render(scope) { MemoThemedRows() }.tree.rows()

        val first = render()
        val second = render()
        assertSame(first[0], second[0])

        memoCurrentTheme = "dark"
        val third = render()
        assertNotSame(first[0], third[0])
        assertEquals("one:dark", third[0].findText().value)
    }

    @Test
    fun externalCellReadOnlyByCachedRowsStillInvalidatesRuntime() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        memoItems = listOf(MemoItem(1, "one"))
        memoExternal.value = "hello"

        fun render(): List<HostNode> = runtime.render(scope) { MemoExternalRows() }.tree.rows()

        render()
        // Second render serves the row from cache; the runtime must keep observing the
        // external cell even though no live content read it this render.
        render()
        memoExternal.value = "bye"
        assertTrue(runtime.hasPendingInvalidation)
        val third = render()
        assertEquals("bye", third[0].findText().value)
    }

    @Test
    fun memoizeOptOutRebuildsEveryRender() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        memoItems = listOf(MemoItem(1, "one"))

        fun render(): List<HostNode> = runtime.render(scope) { MemoOptOutRows() }.tree.rows()

        assertNotSame(render()[0], render()[0])
    }

    @Test
    fun rowsWithEffectsAreNotMemoized() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        memoItems = listOf(MemoItem(1, "one"))

        fun render(): List<HostNode> = runtime.render(scope) { MemoEffectRows() }.tree.rows()

        assertNotSame(render()[0], render()[0])
    }

    @Test
    fun nestedEachMemoizesInnerRowsWhileOuterRebuilds() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        memoGroups = listOf("g1" to listOf("a", "b"))

        fun render(): HostNode = runtime.render(scope) { MemoNestedRows() }.tree.rows().single()

        val first = render()
        val second = render()

        // The outer row contains a nested each, so it rebuilds every render...
        assertNotSame(first, second)
        // ...but the inner rows come from the inner cache, reference-equal.
        first.children.zip(second.children).forEach { (before, after) -> assertSame(before, after) }
    }

    @Test
    fun rowRemovedWhileSiblingTurnsNonMemoizableStillEvictsAndRebindsEvents() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val a = MemoEffectRow(1, "a")
        val b = MemoEffectRow(2, "b")
        memoEffectRows = listOf(a, b)
        memoEffectRowClicks.clear()

        fun render(): List<HostNode> = runtime.render(scope) { MemoConditionalEffectRows() }.tree.rows()

        val first = render()

        // Row a leaves the list in the same render that row b turns non-memoizable; a's
        // keyed frame must still be disposed (its runtime event handlers are gone after
        // commit) so a later re-add rebuilds instead of replaying dead events.
        memoEffectRows = listOf(MemoEffectRow(2, "b", effect = true))
        render()

        memoEffectRows = listOf(a, MemoEffectRow(2, "b"))
        val third = render()

        assertNotSame(first[0], third[0])
        runtime.dispatch(third[0].findClickEventId())
        assertEquals(listOf(1), memoEffectRowClicks)
    }

    @Test
    fun duplicateKeysKeepLastWinsUnderMemoization() {
        val runtime = KineticaRuntime(debug = false)
        val scope = ComponentScope(runtime)
        memoItems = listOf(MemoItem(1, "first"), MemoItem(1, "second"))

        fun render(): List<HostNode> = runtime.render(scope) { MemoBasicRows() }.tree.rows()

        val first = render()
        val second = render()

        assertEquals(1, second.size)
        assertEquals("second", second.single().findText().value)
        assertSame(first.single(), second.single())
    }
}
