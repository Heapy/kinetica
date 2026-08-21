package io.heapy.kinetica

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
    private val exitLock = platformLock()
    private val exitLog = mutableListOf<String>()

    fun recordExit(item: String) {
        synchronizedOn(exitLock) {
            exitLog += item
        }
    }

    fun exitsSnapshot(): List<String> =
        synchronizedOn(exitLock) {
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

private class BatchedRowsProbe(
    var batches: List<List<Int>> = emptyList(),
) {
    val inits = mutableListOf<Any>()

    fun initCount(key: Any): Int =
        inits.count { seen -> seen == key }
}

@UiComponent(skippable = false)
private fun ComponentScope.BatchedRowsApp(probe: BatchedRowsProbe) {
    column {
        for ((index, batch) in probe.batches.withIndex()) {
            each(batch, key = { item -> item }, memoize = false) { item ->
                var count by state {
                    probe.inits += item
                    0
                }
                host("li", key = "$index:$item") {
                    text("$index:$item:$count", semantics = null)
                    button(onClick = { count += 1 }, semantics = null) {
                        text("+", semantics = null)
                    }
                }
            }
        }
    }
}

private class KeyedLoopProbe(
    var labels: List<String> = emptyList(),
) {
    var inits = 0
}

@UiComponent(skippable = false)
private fun ComponentScope.KeyedLoopApp(probe: KeyedLoopProbe) {
    column {
        for ((index, label) in probe.labels.withIndex()) {
            keyed("shared") {
                var count by state {
                    probe.inits += 1
                    0
                }
                host("section", key = "pass-$index") {
                    text("$label:$count", semantics = null)
                    button(onClick = { count += 1 }, semantics = null) {
                        text("+", semantics = null)
                    }
                }
            }
        }
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
        assertEquals(listOf("A:again:3"), tree.rowTexts())
        assertEquals(2, probe.inits)
    }

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

    @Test
    fun eachInsideLoopPreservesRowStateAcrossRenders() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = BatchedRowsProbe(listOf(listOf(1, 2), listOf(3, 4)))

        fun render(): Node = runtime.render(scope) { BatchedRowsApp(probe) }.tree

        var tree = render()
        assertEquals(4, probe.inits.size)
        assertEquals(listOf("0:1:0", "0:2:0", "1:3:0", "1:4:0"), tree.rowTexts())

        tree = render()
        assertEquals(4, probe.inits.size)

        runtime.dispatch(tree.rows()[0].findClickEventId())
        runtime.dispatch(tree.rows()[3].findClickEventId())
        tree = render()
        assertEquals(4, probe.inits.size)
        assertEquals(listOf("0:1:1", "0:2:0", "1:3:0", "1:4:1"), tree.rowTexts())
    }

    @Test
    fun eachInsideLoopWithSingleBatchKeepsExistingBehavior() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = BatchedRowsProbe(listOf(listOf(1, 2, 3, 4)))

        fun render(): Node = runtime.render(scope) { BatchedRowsApp(probe) }.tree

        render()
        render()
        val tree = render()
        assertEquals(4, probe.inits.size)
        assertEquals(listOf("0:1:0", "0:2:0", "0:3:0", "0:4:0"), tree.rowTexts())
    }

    @Test
    fun eachInvocationsSharingUserKeysKeepIndependentRowState() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = BatchedRowsProbe(listOf(listOf(1, 2), listOf(1, 2)))

        fun render(): Node = runtime.render(scope) { BatchedRowsApp(probe) }.tree

        var tree = render()
        assertEquals(4, probe.inits.size)
        assertEquals(listOf("0:1:0", "0:2:0", "1:1:0", "1:2:0"), tree.rowTexts())

        runtime.dispatch(tree.rows()[0].findClickEventId())
        tree = render()
        assertEquals(listOf("0:1:1", "0:2:0", "1:1:0", "1:2:0"), tree.rowTexts())
        assertEquals(4, probe.inits.size)
    }

    @Test
    fun eachInsideLoopStillDisposesRowsWhoseKeysLeave() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = BatchedRowsProbe(listOf(listOf(1, 2), listOf(3, 4)))

        fun render(): Node = runtime.render(scope) { BatchedRowsApp(probe) }.tree

        render()
        assertEquals(4, probe.inits.size)

        probe.batches = listOf(listOf(1), listOf(3))
        var tree = render()
        assertEquals(listOf("0:1:0", "1:3:0"), tree.rowTexts())

        probe.batches = listOf(listOf(1, 2), listOf(3, 4))
        tree = render()
        assertEquals(listOf("0:1:0", "0:2:0", "1:3:0", "1:4:0"), tree.rowTexts())
        assertEquals(1, probe.initCount(1))
        assertEquals(2, probe.initCount(2))
        assertEquals(1, probe.initCount(3))
        assertEquals(2, probe.initCount(4))
    }

    @Test
    fun vanishedEachInvocationDisposesItsRows() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = BatchedRowsProbe(listOf(listOf(1, 2), listOf(3, 4)))

        fun render(): Node = runtime.render(scope) { BatchedRowsApp(probe) }.tree

        render()
        assertEquals(4, probe.inits.size)

        probe.batches = listOf(listOf(1, 2))
        var tree = render()
        assertEquals(listOf("0:1:0", "0:2:0"), tree.rowTexts())

        probe.batches = listOf(listOf(1, 2), listOf(3, 4))
        tree = render()
        assertEquals(listOf("0:1:0", "0:2:0", "1:3:0", "1:4:0"), tree.rowTexts())
        assertEquals(1, probe.initCount(1))
        assertEquals(1, probe.initCount(2))
        assertEquals(2, probe.initCount(3))
        assertEquals(2, probe.initCount(4))
    }

    @Test
    fun keyedInvocationsSharingKeyInLoopKeepIndependentState() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = KeyedLoopProbe(listOf("a", "b"))

        fun render(): Node = runtime.render(scope) { KeyedLoopApp(probe) }.tree

        var tree = render()
        assertEquals(2, probe.inits)

        runtime.dispatch(tree.rows()[0].findClickEventId())
        tree = render()
        assertEquals(listOf("a:1", "b:0"), tree.rowTexts())
        assertEquals(2, probe.inits)

        probe.labels = listOf("a")
        tree = render()
        assertEquals(listOf("a:1"), tree.rowTexts())

        probe.labels = listOf("a", "b")
        tree = render()
        assertEquals(listOf("a:1", "b:0"), tree.rowTexts())
        assertEquals(2, probe.inits)
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
