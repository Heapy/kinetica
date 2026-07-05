package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EachMemoizationTest {
    private data class Item(val id: Int, val label: String)

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
        val items = listOf(Item(1, "one"), Item(2, "two"), Item(3, "three"))

        fun render(): Node = runtime.render(scope) {
            column {
                each(items, key = { it.id }) { item ->
                    host("li", key = item.id) {
                        text(item.label, semantics = null)
                    }
                }
            }
        }.tree

        val first = render().rows()
        val second = render().rows()

        assertEquals(3, second.size)
        first.zip(second).forEach { (before, after) -> assertSame(before, after) }
    }

    @Test
    fun changedItemRebuildsOnlyItsRow() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var items = listOf(Item(1, "one"), Item(2, "two"), Item(3, "three"))

        fun render(): List<HostNode> = runtime.render(scope) {
            column {
                each(items, key = { it.id }) { item ->
                    host("li", key = item.id) {
                        text(item.label, semantics = null)
                    }
                }
            }
        }.tree.rows()

        val first = render()
        items = listOf(items[0], Item(2, "TWO"), items[2])
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
        val one = Item(1, "one")
        val two = Item(2, "two")
        val three = Item(3, "three")
        var items = listOf(one, two, three)

        fun render(): List<HostNode> = runtime.render(scope) {
            column {
                each(items, key = { it.id }) { item ->
                    host("li", key = item.id) {
                        text(item.label, semantics = null)
                    }
                }
            }
        }.tree.rows()

        val first = render()
        items = listOf(three, one, two)
        val second = render()

        assertSame(first[2], second[0])
        assertSame(first[0], second[1])
        assertSame(first[1], second[2])
    }

    @Test
    fun cellReadInsideRowInvalidatesOnlyReadingRow() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val items = listOf(Item(1, "one"), Item(2, "two"))
        val cells = mutableMapOf<Int, MutableCell<Boolean>>()

        fun render(): List<HostNode> = runtime.render(scope) {
            column {
                each(items, key = { it.id }) { item ->
                    val selected = state(key = "selected") { false }
                    cells[item.id] = selected
                    host("li", key = item.id) {
                        text("${item.label}:${selected.value}", semantics = null)
                    }
                }
            }
        }.tree.rows()

        val first = render()
        cells.getValue(2).value = true
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
        val items = listOf(Item(1, "one"), Item(2, "two"))
        val clicks = mutableListOf<Int>()

        fun render(): List<HostNode> = runtime.render(scope) {
            column {
                each(items, key = { it.id }) { item ->
                    host("li", key = item.id) {
                        button(onClick = { clicks += item.id }) {
                            text(item.label, semantics = null)
                        }
                    }
                }
            }
        }.tree.rows()

        render()
        val registeredAfterFirst = runtime.registeredEventCount()
        val second = render()
        val third = render()

        assertEquals(registeredAfterFirst, runtime.registeredEventCount())
        assertSame(second[1], third[1])
        runtime.dispatch(third[1].findClickEventId())
        assertEquals(listOf(2), clicks)
    }

    @Test
    fun removedRowEvictsCacheAndReaddedRowRebuilds() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val one = Item(1, "one")
        val two = Item(2, "two")
        var items = listOf(one, two)
        val clicks = mutableListOf<Int>()

        fun render(): List<HostNode> = runtime.render(scope) {
            column {
                each(items, key = { it.id }) { item ->
                    host("li", key = item.id) {
                        button(onClick = { clicks += item.id }) {
                            text(item.label, semantics = null)
                        }
                    }
                }
            }
        }.tree.rows()

        val first = render()
        items = listOf(one)
        render()
        assertEquals(1, runtime.registeredEventCount())

        items = listOf(one, two)
        val third = render()

        assertSame(first[0], third[0])
        assertNotSame(first[1], third[1])
        runtime.dispatch(third[1].findClickEventId())
        assertEquals(listOf(2), clicks)
    }

    @Test
    fun siblingSlotKeysStayStableWhenRowsSkip() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var items = listOf(Item(1, "one"), Item(2, "two"))

        fun render(): List<HostNode> = runtime.render(scope) {
            column {
                each(items, key = { it.id }) { item ->
                    // Positional slot: its key embeds the render-global cursor, so a
                    // skipped sibling must reserve its cursor positions for this row's
                    // state to survive a later rebuild.
                    var count by state { 0 }
                    host("li", key = item.id) {
                        text("${item.label}:$count", semantics = null)
                        button(onClick = { count += 1 }) {
                            text("+", semantics = null)
                        }
                    }
                }
            }
        }.tree.rows()

        val first = render()
        // Row 1 stays memoized while row 2's counter is bumped and its item relabeled.
        runtime.dispatch(first[1].findClickEventId())
        val second = render()
        assertEquals("two:1", second[1].findText().value)
        assertSame(first[0], second[0])

        items = listOf(items[0], Item(2, "TWO"))
        val third = render()
        assertSame(first[0], third[0])
        assertEquals("TWO:1", third[1].findText().value)
    }

    @Test
    fun contextChangeInvalidatesRowCache() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val theme = context("light")
        var current = "light"
        val items = listOf(Item(1, "one"))

        fun render(): List<HostNode> = runtime.render(scope) {
            provide(theme, current) {
                column {
                    each(items, key = { it.id }) { item ->
                        host("li", key = item.id) {
                            text("${item.label}:${read(theme)}", semantics = null)
                        }
                    }
                }
            }
        }.tree.rows()

        val first = render()
        val second = render()
        assertSame(first[0], second[0])

        current = "dark"
        val third = render()
        assertNotSame(first[0], third[0])
        assertEquals("one:dark", third[0].findText().value)
    }

    @Test
    fun externalCellReadOnlyByCachedRowsStillInvalidatesRuntime() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val external = store("hello")
        val items = listOf(Item(1, "one"))

        fun render(): List<HostNode> = runtime.render(scope) {
            column {
                each(items, key = { it.id }) { item ->
                    host("li", key = item.id) {
                        text(external.value, semantics = null)
                    }
                }
            }
        }.tree.rows()

        render()
        // Second render serves the row from cache; the runtime must keep observing the
        // external cell even though no live content read it this render.
        render()
        external.value = "bye"
        assertTrue(runtime.hasPendingInvalidation)
        val third = render()
        assertEquals("bye", third[0].findText().value)
    }

    @Test
    fun memoizeOptOutRebuildsEveryRender() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val items = listOf(Item(1, "one"))

        fun render(): List<HostNode> = runtime.render(scope) {
            column {
                each(items, key = { it.id }, memoize = false) { item ->
                    host("li", key = item.id) {
                        text(item.label, semantics = null)
                    }
                }
            }
        }.tree.rows()

        assertNotSame(render()[0], render()[0])
    }

    @Test
    fun rowsWithEffectsAreNotMemoized() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val items = listOf(Item(1, "one"))

        fun render(): List<HostNode> = runtime.render(scope) {
            column {
                each(items, key = { it.id }) { item ->
                    launchEffect { }
                    host("li", key = item.id) {
                        text(item.label, semantics = null)
                    }
                }
            }
        }.tree.rows()

        assertNotSame(render()[0], render()[0])
    }

    @Test
    fun nestedEachMemoizesInnerRowsWhileOuterRebuilds() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val groups = listOf("g1" to listOf("a", "b"))

        fun render(): HostNode = runtime.render(scope) {
            column {
                each(groups, key = { it.first }) { group ->
                    host("section", key = group.first) {
                        each(group.second, key = { it }) { name ->
                            host("li", key = name) {
                                text(name, semantics = null)
                            }
                        }
                    }
                }
            }
        }.tree.rows().single()

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

        data class Row(val id: Int, val label: String, val effect: Boolean = false)

        val a = Row(1, "a")
        val b = Row(2, "b")
        var items = listOf(a, b)
        val clicks = mutableListOf<Int>()

        fun render(): List<HostNode> = runtime.render(scope) {
            column {
                each(items, key = { it.id }) { item ->
                    if (item.effect) {
                        launchEffect { }
                    }
                    host("li", key = item.id) {
                        button(onClick = { clicks += item.id }) {
                            text(item.label, semantics = null)
                        }
                    }
                }
            }
        }.tree.rows()

        val first = render()

        // Row a leaves the list in the same render that row b turns non-memoizable, so the
        // callsite cache and seen-key set have EQUAL sizes with different contents; a's
        // entry must still be evicted (its runtime event handlers are gone after commit).
        items = listOf(Row(2, "b", effect = true))
        render()

        items = listOf(a, Row(2, "b"))
        val third = render()

        assertNotSame(first[0], third[0])
        runtime.dispatch(third[0].findClickEventId())
        assertEquals(listOf(1), clicks)
    }

    @Test
    fun duplicateKeysKeepLastWinsUnderMemoization() {
        val runtime = KineticaRuntime(debug = false)
        val scope = ComponentScope(runtime)
        val items = listOf(Item(1, "first"), Item(1, "second"))

        fun render(): List<HostNode> = runtime.render(scope) {
            column {
                each(items, key = { it.id }) { item ->
                    host("li", key = item.id) {
                        text(item.label, semantics = null)
                    }
                }
            }
        }.tree.rows()

        val first = render()
        val second = render()

        assertEquals(1, second.size)
        assertEquals("second", second.single().findText().value)
        assertSame(first.single(), second.single())
    }
}
