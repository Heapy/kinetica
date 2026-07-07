package io.heapy.kinetica

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

private data class IdentityItem(
    val key: Any,
    val label: String = key.toString(),
    val clickValue: Int? = null,
    val clickDelta: Int = 1,
    val hostKey: Any = key,
)

private class IdentityRowsProbe(
    var items: List<IdentityItem> = emptyList(),
) {
    val inits = mutableListOf<Any>()

    fun initCount(key: Any): Int =
        inits.count { seen -> seen == key }
}

@UiComponent(skippable = false)
private fun ComponentScope.IdentityRowsApp(probe: IdentityRowsProbe) {
    column {
        each(probe.items, key = { item -> item.key }) { item ->
            IdentityStateRow(item, probe)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.IdentityStateRow(item: IdentityItem, probe: IdentityRowsProbe) {
    var count by state {
        probe.inits += item.key
        0
    }
    host("li", key = item.hostKey) {
        text("${item.label}:$count", semantics = null)
        button(
            onClick = {
                val next = item.clickValue
                if (next == null) {
                    count += item.clickDelta
                } else {
                    count = next
                }
            },
            semantics = null,
        ) {
            text("+", semantics = null)
        }
    }
}

private class SkipOrderProbe(
    var items: List<String> = emptyList(),
) {
    val renders = mutableListOf<String>()
}

@UiComponent(skippable = false)
private fun ComponentScope.SkipOrderApp(probe: SkipOrderProbe) {
    column {
        each(probe.items, key = { item -> item }, memoize = true) { item ->
            SkipEligibleRow(item, probe)
        }
    }
}

@UiComponent
private fun ComponentScope.SkipEligibleRow(item: String, probe: SkipOrderProbe) {
    probe.renders += item
    host("li", key = item) {
        text(item, semantics = null)
    }
}

private class DuplicateKeyProbe(
    var items: List<Int> = emptyList(),
)

@UiComponent(skippable = false)
private fun ComponentScope.DuplicateKeyApp(probe: DuplicateKeyProbe) {
    column {
        each(probe.items, key = { item -> item }) { item ->
            DuplicateKeyRow(item)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.DuplicateKeyRow(item: Int) {
    host("li", key = item) {
        text(item.toString(), semantics = null)
    }
}

private class KeyedBranchProbe {
    var currentKey = "A"
    var label = "one"
    var inits = 0
}

@UiComponent(skippable = false)
private fun ComponentScope.KeyedBranchApp(probe: KeyedBranchProbe) {
    column {
        keyed(probe.currentKey) {
            var count by state {
                probe.inits += 1
                0
            }
            host("section", key = probe.currentKey) {
                text("${probe.currentKey}:${probe.label}:$count", semantics = null)
                button(onClick = { count += 1 }, semantics = null) {
                    text("+", semantics = null)
                }
            }
        }
    }
}

private class RowExitProbe(
    var items: List<String> = emptyList(),
) {
    private val exitLock = SynchronizedObject()
    private val exitLog = mutableListOf<String>()

    fun recordExit(item: String) {
        synchronized(exitLock) {
            exitLog += item
        }
    }

    fun exitsSnapshot(): List<String> =
        synchronized(exitLock) {
            exitLog.toList()
        }
}

@UiComponent(skippable = false)
private fun ComponentScope.RowExitApp(probe: RowExitProbe) {
    column {
        each(probe.items, key = { item -> item }) { item ->
            RowWithExitEffect(item, probe)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.RowWithExitEffect(item: String, probe: RowExitProbe) {
    launchEffect {
        awaitDispose {
            probe.recordExit(item)
        }
    }
    host("li", key = item) {
        text(item, semantics = null)
    }
}

private data class StableKey(val id: Int)

class EachIdentitySemanticsTest {
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

    private suspend fun awaitExits(probe: RowExitProbe, expected: List<String>) {
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (probe.exitsSnapshot() != expected) {
                    delay(10)
                }
            }
        }
        assertEquals(expected, probe.exitsSnapshot())
    }

    /**
     * KSND-036 (sources: RCT-003, PRE-018, INF-041).
     */
    @Test
    fun keyChangeAtSamePositionDiscardsRowState() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = IdentityRowsProbe(listOf(IdentityItem("a", label = "row")))

        fun render(): Node = runtime.render(scope) { IdentityRowsApp(probe) }.tree

        var tree = render()
        assertEquals(1, probe.inits.size)
        repeat(5) {
            runtime.dispatch(tree.rows().single().findClickEventId())
        }

        tree = render()
        assertEquals(listOf("row:5"), tree.rowTexts())

        probe.items = listOf(IdentityItem("b", label = "row"))
        tree = render()
        assertEquals(2, probe.inits.size)
        assertEquals(listOf("row:0"), tree.rowTexts())

        probe.items = listOf(IdentityItem("a", label = "row"))
        tree = render()
        assertEquals(3, probe.inits.size)
        assertEquals(2, probe.initCount("a"))
        assertEquals(1, probe.initCount("b"))
        assertEquals(listOf("row:0"), tree.rowTexts())
    }

    /**
     * KSND-037 (sources: RCT-008).
     */
    @Test
    fun removedThenReaddedKeyGetsFreshRowWithoutResurrectingState() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val x = IdentityItem("x")
        val y = IdentityItem("y")
        val probe = IdentityRowsProbe(listOf(x, y))

        fun render(): Node = runtime.render(scope) { IdentityRowsApp(probe) }.tree

        var tree = render()
        val yBefore = tree.rows()[1]
        runtime.dispatch(tree.rows()[0].findClickEventId())
        tree = render()
        assertEquals(listOf("x:1", "y:0"), tree.rowTexts())

        probe.items = listOf(y)
        tree = render()
        assertSame(yBefore, tree.rows().single())
        assertEquals(listOf("y:0"), tree.rowTexts())

        probe.items = listOf(x, y)
        tree = render()
        assertEquals(2, probe.initCount("x"))
        assertEquals(1, probe.initCount("y"))
        assertEquals(listOf("x:0", "y:0"), tree.rowTexts())
        assertSame(yBefore, tree.rows()[1])
    }

    /**
     * KSND-038 (sources: RCT-010, RCT-009, PRE-021, SVL-042).
     */
    @Test
    fun rotationPreservesPerRowStateAtEveryStep() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var order = listOf(1, 2, 3, 4)
        val probe = IdentityRowsProbe(numberItems(order))

        fun render(): Node = runtime.render(scope) { IdentityRowsApp(probe) }.tree

        var tree = render()
        tree.rows().forEach { row -> runtime.dispatch(row.findClickEventId()) }
        tree = render()
        assertEquals(listOf("1:10", "2:20", "3:30", "4:40"), tree.rowTexts())
        assertEquals(4, probe.inits.size)

        repeat(4) {
            order = order.drop(1) + order.take(1)
            probe.items = numberItems(order)
            tree = render()
            assertEquals(order.map { key -> "$key:${key * 10}" }, tree.rowTexts())
            assertEquals(4, probe.inits.size)
        }
    }

    /**
     * KSND-039 (sources: RCT-014, PRE-017).
     */
    @Test
    fun reorderOfSkipEligibleRowsStillAppliesNewOrder() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = SkipOrderProbe(listOf("X", "K", "W", "H"))

        fun render(): List<HostNode> = runtime.render(scope) { SkipOrderApp(probe) }.tree.rows()

        val first = render()
        assertEquals(listOf("X", "K", "W", "H"), first.map { row -> row.findText().value })
        assertEquals(listOf("X", "K", "W", "H"), probe.renders)

        probe.items = listOf("H", "W", "K", "X")
        val second = render()
        assertEquals(listOf("H", "W", "K", "X"), second.map { row -> row.findText().value })
        assertSame(first[3], second[0])
        assertSame(first[2], second[1])
        assertSame(first[1], second[2])
        assertSame(first[0], second[3])
        assertEquals(listOf("X", "K", "W", "H"), probe.renders)
    }

    /**
     * KSND-040 (sources: SVL-024, PRE-001).
     */
    @Test
    fun sameKeysWithNewItemDataUpdateRowsWithoutResettingState() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = IdentityRowsProbe(
            listOf(
                IdentityItem(1, label = "milk"),
                IdentityItem(2, label = "bread"),
            ),
        )

        fun render(): Node = runtime.render(scope) { IdentityRowsApp(probe) }.tree

        var tree = render()
        runtime.dispatch(tree.rows()[0].findClickEventId())
        runtime.dispatch(tree.rows()[1].findClickEventId())
        runtime.dispatch(tree.rows()[1].findClickEventId())
        tree = render()
        assertEquals(listOf("milk:1", "bread:2"), tree.rowTexts())

        probe.items = listOf(
            IdentityItem(1, label = "beer"),
            IdentityItem(2, label = "toast"),
        )
        tree = render()
        assertEquals(2, probe.inits.size)
        assertEquals(listOf("beer:1", "toast:2"), tree.rowTexts())
    }

    /**
     * KSND-041 (sources: RCT-007, SVL-039, VUE-111).
     */
    @Test
    fun duplicateKeysProduceDeterministicDiagnosticOnMountAndUpdate() {
        val mountRuntime = KineticaRuntime()
        val mountScope = ComponentScope(mountRuntime)
        val mountProbe = DuplicateKeyProbe(listOf(1, 2, 3, 1))

        val mountFailure = assertFailsWith<IllegalStateException> {
            mountRuntime.render(mountScope) { DuplicateKeyApp(mountProbe) }
        }
        assertEquals("Duplicate key: 1", mountFailure.message)
        assertEquals("1", mountRuntime.warnings().single { warning -> warning.code == "duplicate-key" }.attributes["key"])

        val updateRuntime = KineticaRuntime()
        val updateScope = ComponentScope(updateRuntime)
        val updateProbe = DuplicateKeyProbe(listOf(1, 2, 3))

        fun renderUpdate(): Node = updateRuntime.render(updateScope) { DuplicateKeyApp(updateProbe) }.tree

        assertEquals(listOf("1", "2", "3"), renderUpdate().rowTexts())
        updateProbe.items = listOf(1, 2, 3, 1)
        val updateFailure = assertFailsWith<IllegalStateException> {
            renderUpdate()
        }
        assertEquals("Duplicate key: 1", updateFailure.message)
        assertEquals("1", updateRuntime.warnings().single { warning -> warning.code == "duplicate-key" }.attributes["key"])
    }

    /**
     * KSND-042 (sources: INF-015, INF-016).
     */
    @Test
    fun keyTypeAndContentDistinctnessKeepRowsSeparate() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val hostile = "a/b\\c&<d>"
        val probe = IdentityRowsProbe(
            listOf(
                typedKeyItem(1),
                typedKeyItem("1"),
            ),
        )

        fun render(): Node = runtime.render(scope) { IdentityRowsApp(probe) }.tree

        var tree = render()
        assertEquals(2, tree.rows().size)
        tree.rows().forEach { row -> runtime.dispatch(row.findClickEventId()) }
        tree = render()
        assertEquals(listOf("Int:1:10", "String:1:20"), tree.rowTexts())

        probe.items = listOf(typedKeyItem(hostile), typedKeyItem("1"), typedKeyItem(1))
        tree = render()
        assertEquals(3, tree.rows().size)
        assertEquals(listOf("String:$hostile:0", "String:1:20", "Int:1:10"), tree.rowTexts())
        assertEquals(1, probe.initCount(1))
        assertEquals(1, probe.initCount("1"))
        assertEquals(1, probe.initCount(hostile))

        probe.items = listOf(typedKeyItem(hostile), typedKeyItem(1), typedKeyItem("1"))
        tree = render()
        assertEquals(listOf("String:$hostile:0", "Int:1:10", "String:1:20"), tree.rowTexts())
        assertEquals(3, probe.inits.size)
    }

    /**
     * KSND-043 (sources: SOL-062, SOL-061, RCT-005, PRE-124, PRE-125).
     */
    @Test
    fun keyedBranchUsesSourceKeyedFrameIdentityContract() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = KeyedBranchProbe()

        fun render(): Node = runtime.render(scope) { KeyedBranchApp(probe) }.tree

        var tree = render()
        repeat(3) {
            runtime.dispatch(tree.rows().single().findClickEventId())
        }
        tree = render()
        assertEquals(listOf("A:one:3"), tree.rowTexts())
        assertEquals(1, probe.inits)

        probe.label = "two"
        tree = render()
        assertEquals(listOf("A:two:3"), tree.rowTexts())
        assertEquals(1, probe.inits)

        probe.currentKey = "B"
        probe.label = "bee"
        tree = render()
        assertEquals(listOf("B:bee:0"), tree.rowTexts())
        assertEquals(2, probe.inits)

        probe.currentKey = "A"
        probe.label = "again"
        tree = render()
        // Keyed frames DEACTIVATE and retain non-transient state when their key leaves.
        // Returning to the key re-activates it by design (Frame.deactivate),
        // unlike React/Inferno remount semantics.
        assertEquals(listOf("A:again:3"), tree.rowTexts())
        assertEquals(2, probe.inits)
    }

    /**
     * KSND-044 (sources: RCT-414, INF-108, SVL-110).
     */
    @Test
    fun rowDisposalOnKeyExitRunsExactlyOnce() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = RowExitProbe(listOf("a", "b", "c"))

        fun render(): Node = runtime.render(scope) { RowExitApp(probe) }.tree

        try {
            assertEquals(listOf("a", "b", "c"), render().rowTexts())
            runtime.awaitIdle()

            probe.items = listOf("a", "c")
            assertEquals(listOf("a", "c"), render().rowTexts())
            awaitExits(probe, listOf("b"))

            probe.items = listOf("a")
            assertEquals(listOf("a"), render().rowTexts())
            awaitExits(probe, listOf("b", "c"))

            probe.items = emptyList()
            assertEquals(emptyList(), render().rowTexts())
            awaitExits(probe, listOf("b", "c", "a"))

            runtime.awaitIdle()
            assertEquals(emptyList(), render().rowTexts())
            awaitExits(probe, listOf("b", "c", "a"))
        } finally {
            scope.dispose()
            runtime.dispose()
        }
    }

    /**
     * KSND-045 (sources: SVL-040, SVL-023).
     */
    @Test
    fun valueEqualKeyObjectsRetainAndMoveRows() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = IdentityRowsProbe(stableKeyItems(listOf(1, 2, 3)))

        fun render(): Node = runtime.render(scope) { IdentityRowsApp(probe) }.tree

        var tree = render()
        tree.rows().forEach { row -> runtime.dispatch(row.findClickEventId()) }
        tree = render()
        assertEquals(listOf("k1:10", "k2:20", "k3:30"), tree.rowTexts())
        assertEquals(3, probe.inits.size)

        listOf(
            listOf(3, 1, 2),
            listOf(2, 3, 1),
            listOf(1, 2, 3),
        ).forEach { ids ->
            probe.items = stableKeyItems(ids)
            tree = render()
            assertEquals(ids.map { id -> "k$id:${id * 10}" }, tree.rowTexts())
            assertEquals(3, probe.inits.size)
        }
    }

    private fun numberItems(keys: List<Int>): List<IdentityItem> =
        keys.map { key -> IdentityItem(key, label = key.toString(), clickValue = key * 10) }

    private fun typedKeyItem(key: Any): IdentityItem =
        when (key) {
            is Int -> IdentityItem(
                key = key,
                label = "Int:$key",
                clickValue = 10,
                hostKey = key,
            )
            is String -> IdentityItem(
                key = key,
                label = "String:$key",
                clickValue = if (key == "1") 20 else 30,
                hostKey = key,
            )
            else -> error("Unsupported key: $key")
        }

    private fun stableKeyItems(ids: List<Int>): List<IdentityItem> =
        ids.map { id ->
            IdentityItem(
                key = StableKey(id),
                label = "k$id",
                clickValue = id * 10,
            )
        }
}
