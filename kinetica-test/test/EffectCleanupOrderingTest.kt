package io.heapy.kinetica.testing

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.JournalKind
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.button
import io.heapy.kinetica.column
import io.heapy.kinetica.derived
import io.heapy.kinetica.each
import io.heapy.kinetica.event
import io.heapy.kinetica.exitGroup
import io.heapy.kinetica.launchEffect
import io.heapy.kinetica.layoutEffect
import io.heapy.kinetica.onExit
import io.heapy.kinetica.state
import io.heapy.kinetica.store
import io.heapy.kinetica.text
import io.heapy.kinetica.watch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EffectCleanupOrderingTest {
    /**
     * KSND-090 (sources: SVL-110, PRE-108, PRE-109, SOL-012, VUE-068).
     */
    @Test
    fun launchEffectPairsInitAndCancelAcrossBranchToggles() = runTest {
        val probe = EffectLogProbe()
        val root = KineticaTest.render {
            BranchToggleEffectApp(probe)
        }

        try {
            waitForLog(probe, listOf("init"))

            root.click(hasTestTag("toggle"))
            waitForLog(probe, listOf("init", "cancel"))

            root.click(hasTestTag("toggle"))
            waitForLog(probe, listOf("init", "cancel", "init"))

            root.click(hasTestTag("toggle"))
            waitForLog(probe, listOf("init", "cancel", "init", "cancel"))
        } finally {
            root.dispose()
        }
    }

    /**
     * KSND-091 (sources: VUE-067, VUE-072, SVL-087, SOL-004).
     */
    @Test
    fun watchStopsAfterOwningBranchIsDisposedAndRearmsOnReturn() = runTest {
        val probe = WatchBranchProbe()
        val root = KineticaTest.render {
            WatchBranchApp(probe)
        }

        try {
            waitForLog(probe, listOf("watch:0"))

            root.click(hasTestTag("toggle"))
            root.settle()
            val afterRemoval = probe.log.toList()

            probe.shared.value = 1
            probe.shared.value = 2
            root.settle()
            delayBriefly()
            assertEquals(afterRemoval, probe.log.toList())

            root.click(hasTestTag("toggle"))
            waitForLog(probe, afterRemoval + "watch:2")

            probe.shared.value = 3
            root.settle()
            waitForLog(probe, afterRemoval + listOf("watch:2", "watch:3"))
        } finally {
            root.dispose()
        }
    }

    /**
     * KSND-092 (sources: RCT-414, VUE-131).
     */
    @Test
    fun removingOneKeyedRowCancelsOnlyThatRowsEffect() = runTest {
        val probe = KeyedRowsProbe(rows = listOf("A", "B"))
        val root = KineticaTest.render {
            RemovableRowsEffectApp(probe)
        }

        try {
            waitUntil {
                probe.log.count { it == "init A" } == 1 &&
                    probe.log.count { it == "init B" } == 1
            }
            val beforeRemoval = probe.log.toList()

            root.click(hasTestTag("remove-a"))
            waitUntil { probe.log.count { it == "cancel A" } == 1 }

            assertEquals(beforeRemoval + "cancel A", probe.log.toList())
            assertEquals(0, probe.log.count { it == "cancel B" })

            probe.bValue.value = 1
            root.settle()
            assertEquals("B:1", root.node(hasText("B:1")).node.let { (it as io.heapy.kinetica.TextNode).value })
            assertEquals(0, probe.log.count { it == "cancel B" })
        } finally {
            root.dispose()
        }
    }

    /**
     * KSND-093 (sources: RCT-415).
     */
    @Test
    fun reorderedRowsDoNotChurnEffectsAndAllCleanUpOnDispose() = runTest {
        val probe = KeyedRowsProbe(rows = listOf("A", "B"))
        val root = KineticaTest.render {
            ReorderRowsEffectApp(probe)
        }

        try {
            waitUntil {
                probe.log.count { it == "init A" } == 1 &&
                    probe.log.count { it == "init B" } == 1
            }
            val beforeReorder = probe.log.toList()

            root.click(hasTestTag("reorder"))
            root.settle()
            delayBriefly()

            assertEquals(beforeReorder, probe.log.toList())

            root.dispose()
            waitUntil {
                probe.log.count { it == "cancel A" } == 1 &&
                    probe.log.count { it == "cancel B" } == 1
            }
            assertEquals(1, probe.log.count { it == "cancel A" })
            assertEquals(1, probe.log.count { it == "cancel B" })
        } finally {
            root.dispose()
        }
    }

    /**
     * KSND-094 (sources: RCT-401, RCT-407, RCT-408, SVL-086, PRE-101, INF-102, VUE-186).
     * Kinetica certifies every nested cleanup completes exactly once.
     */
    @Test
    fun nestedDisposalCompletesEveryCleanupExactlyOnce() = runTest {
        val probe = EffectLogProbe()
        val root = KineticaTest.render {
            NestedDisposalApp(probe)
        }

        try {
            waitUntil {
                probe.log.count { it == "init P" } == 1 &&
                    probe.log.count { it == "init C" } == 1 &&
                    probe.log.count { it == "init G" } == 1
            }
            probe.log.clear()

            root.dispose()
            waitUntil { probe.log.count { it.startsWith("cancel ") } == 3 }
            val completed = probe.log.toList()
            // completion order is unspecified by design; certifying an order would require synchronous/joined cleanup (feature).
            assertEquals(setOf("cancel G", "cancel C", "cancel P"), completed.toSet())
            assertEquals(3, completed.size)
            assertEquals(1, completed.count { it == "cancel G" })
            assertEquals(1, completed.count { it == "cancel C" })
            assertEquals(1, completed.count { it == "cancel P" })
        } finally {
            root.dispose()
        }
    }

    /**
     * KSND-095 (sources: infra gap (exitGroup abandonment), RuntimeSmokeSlots).
     */
    @Test
    fun exitGroupAbandonmentCleansRetainedEffectExactlyOnce() = runTest {
        val probe = ExitAbandonmentProbe()
        val root = KineticaTest.render {
            ExitGroupAbandonmentApp(probe)
        }

        try {
            waitForLog(probe, listOf("init"))

            root.click(hasTestTag("hide"))
            withContext(Dispatchers.Default) {
                withTimeout(2_000) {
                    probe.exitStarted.await()
                }
            }
            waitForLog(probe, listOf("init", "cancel"))

            root.dispose()
            val journalSizeAfterDispose = root.journal().size
            root.dispose()
            delayBriefly()

            assertEquals(listOf("init", "cancel"), probe.log.toList())
            assertEquals(journalSizeAfterDispose, root.journal().size)
        } finally {
            root.dispose()
        }
    }

    /**
     * KSND-096 (sources: RCT-411, RCT-412, PRE-116, SVL-081).
     */
    @Test
    fun layoutEffectRunsBeforePostCommitWatchOnInitialAndUpdateCommit() = runTest {
        val probe = EffectLogProbe()
        val root = KineticaTest.render {
            LayoutBeforePostCommitEffectApp(probe)
        }

        try {
            waitForLog(probe, listOf("layout:0", "passive:0"))

            root.click(hasTestTag("bump"))
            waitForLog(probe, listOf("layout:0", "passive:0", "layout:1", "passive:1"))
        } finally {
            root.dispose()
        }
    }

    /**
     * KSND-097 (sources: PRE-106, VUE-206, SVL-011).
     */
    @Test
    fun effectWritingCellSettlesWithOneExtraCommittedRender() = runTest {
        val root = KineticaTest.render {
            EffectWritesCellApp()
        }

        try {
            root.settle()
            val beforeClick = root.committedRenderCount()

            root.click(hasTestTag("copy-source"))
            root.settle()

            assertEquals(beforeClick + 2, root.committedRenderCount())
            assertEquals(
                "Source: 1 Copy: 1",
                root.node(hasText("Source: 1 Copy: 1")).node.let { (it as io.heapy.kinetica.TextNode).value },
            )

            val afterSettle = root.committedRenderCount()
            root.settle()
            assertEquals(afterSettle, root.committedRenderCount())
        } finally {
            root.dispose()
        }
    }

    /**
     * KSND-098 (sources: PRE-107, INF-123, RCT-519, VUE-072).
     */
    @Test
    fun pendingEffectOfRemovedComponentDoesNotLeaveOrphanedInit() = runTest {
        val probe = EffectLogProbe()
        val root = KineticaTest.render {
            PendingEffectRemovalApp(probe)
        }

        try {
            root.click(hasTestTag("toggle"))
            root.click(hasTestTag("toggle"))
            root.settle()
            delayBriefly()

            val log = probe.log.toList()
            assertEquals(log.count { it == "init" }, log.count { it == "cancel" })
            assertTrue(log.isEmpty() || log == listOf("init", "cancel"))
            assertFailsWith<IllegalStateException> {
                root.node(hasTestTag("pending-child"))
            }
        } finally {
            root.dispose()
        }
    }

    /**
     * KSND-099 (sources: VUE-066, VUE-069, VUE-061, SVL-019d, SVL-083).
     */
    @Test
    fun watchBatchingUsesFinalValueAndDeclarationOrder() = runTest {
        val probe = EffectLogProbe()
        val root = KineticaTest.render {
            WatchBatchingApp(probe)
        }

        try {
            waitUntil { probe.log.size == 2 }
            probe.log.clear()

            root.click(hasTestTag("double-bump"))
            waitForLog(
                probe = probe,
                expected = listOf("A:2", "B:2"),
                root = root,
                phase = "after first double-bump",
            )

            root.click(hasTestTag("double-bump"))
            waitForLog(
                probe = probe,
                expected = listOf("A:2", "B:2", "A:4", "B:4"),
                root = root,
                phase = "after second double-bump",
            )
        } finally {
            root.dispose()
        }
    }
}

private class EffectLogProbe {
    val log = ThreadSafeStringLog()
}

private class WatchBranchProbe {
    val log = ThreadSafeStringLog()
    val shared = store(0)
}

private class KeyedRowsProbe(
    rows: List<String>,
) {
    val rows = store(rows)
    val log = ThreadSafeStringLog()
    val bValue = store(0)
}

private class ExitAbandonmentProbe {
    val log = ThreadSafeStringLog()
    val visible = store(true)
    val exitStarted = CompletableDeferred<Unit>()
    val neverExit = CompletableDeferred<Unit>()
}

private class ThreadSafeStringLog {
    private val values = MutableStateFlow<List<String>>(emptyList())

    operator fun plusAssign(value: String) {
        values.update { current -> current + value }
    }

    fun clear() {
        values.update { emptyList() }
    }

    fun toList(): List<String> = values.value

    val size: Int
        get() = values.value.size

    fun count(predicate: (String) -> Boolean): Int =
        values.value.count(predicate)
}

@UiComponent(skippable = false)
private fun ComponentScope.BranchToggleEffectApp(probe: EffectLogProbe) {
    var show by state { true }

    column {
        button(
            onClick = event { show = !show },
            semantics = Semantics(role = Role.Button, testTag = "toggle"),
        ) {
            text("Toggle")
        }
        if (show) {
            BranchToggleEffectChild(probe)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.BranchToggleEffectChild(probe: EffectLogProbe) {
    launchEffect {
        probe.log += "init"
        awaitDispose { probe.log += "cancel" }
    }
    text("Child")
}

@UiComponent(skippable = false)
private fun ComponentScope.WatchBranchApp(probe: WatchBranchProbe) {
    var show by state { true }

    column {
        button(
            onClick = event { show = !show },
            semantics = Semantics(role = Role.Button, testTag = "toggle"),
        ) {
            text("Toggle")
        }
        if (show) {
            WatchBranchChild(probe)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.WatchBranchChild(probe: WatchBranchProbe) {
    watch(source = { probe.shared.value }) { value ->
        probe.log += "watch:$value"
    }
    text("Watching")
}

@UiComponent(skippable = false)
private fun ComponentScope.RemovableRowsEffectApp(probe: KeyedRowsProbe) {
    column {
        button(
            onClick = event { probe.rows.value = probe.rows.value.filterNot { row -> row == "A" } },
            semantics = Semantics(role = Role.Button, testTag = "remove-a"),
        ) {
            text("Remove A")
        }
        KeyedEffectRows(probe)
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.ReorderRowsEffectApp(probe: KeyedRowsProbe) {
    column {
        button(
            onClick = event { probe.rows.value = probe.rows.value.reversed() },
            semantics = Semantics(role = Role.Button, testTag = "reorder"),
        ) {
            text("Reorder")
        }
        KeyedEffectRows(probe)
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.KeyedEffectRows(probe: KeyedRowsProbe) {
    column {
        each(probe.rows.value, key = { row -> row }) { row ->
            KeyedEffectRow(row, probe)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.KeyedEffectRow(row: String, probe: KeyedRowsProbe) {
    launchEffect {
        probe.log += "init $row"
        awaitDispose { probe.log += "cancel $row" }
    }
    val value = if (row == "B") probe.bValue.value else 0
    text("$row:$value")
}

@UiComponent(skippable = false)
private fun ComponentScope.NestedDisposalApp(probe: EffectLogProbe) {
    launchEffect {
        probe.log += "init P"
        awaitDispose { probe.log += "cancel P" }
    }
    NestedChild(probe)
}

@UiComponent(skippable = false)
private fun ComponentScope.NestedChild(probe: EffectLogProbe) {
    launchEffect {
        probe.log += "init C"
        awaitDispose { probe.log += "cancel C" }
    }
    NestedGrandchild(probe)
}

@UiComponent(skippable = false)
private fun ComponentScope.NestedGrandchild(probe: EffectLogProbe) {
    launchEffect {
        probe.log += "init G"
        awaitDispose { probe.log += "cancel G" }
    }
    text("Nested")
}

@UiComponent(skippable = false)
private fun ComponentScope.ExitGroupAbandonmentApp(probe: ExitAbandonmentProbe) {
    column {
        button(
            onClick = event { probe.visible.value = false },
            semantics = Semantics(role = Role.Button, testTag = "hide"),
        ) {
            text("Hide")
        }
        exitGroup(key = "panel", visible = probe.visible.value) {
            onExit {
                probe.exitStarted.complete(Unit)
                probe.neverExit.await()
                complete()
            }
            ExitGroupEffectChild(probe)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.ExitGroupEffectChild(probe: ExitAbandonmentProbe) {
    launchEffect {
        probe.log += "init"
        awaitDispose { probe.log += "cancel" }
    }
    text("Panel")
}

@UiComponent(skippable = false)
private fun ComponentScope.LayoutBeforePostCommitEffectApp(probe: EffectLogProbe) {
    var count by state { 0 }

    layoutEffect {
        probe.log += "layout:$count"
    }
    watch(source = { count }) { value ->
        probe.log += "passive:$value"
    }
    column {
        button(
            onClick = event { count += 1 },
            semantics = Semantics(role = Role.Button, testTag = "bump"),
        ) {
            text("Bump")
        }
        text("Count: $count")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.EffectWritesCellApp() {
    var source by state { 0 }
    var copy by state { 0 }

    watch(source = { source }) { value ->
        if (copy != value) {
            copy = value
        }
    }
    column {
        button(
            onClick = event { source += 1 },
            semantics = Semantics(role = Role.Button, testTag = "copy-source"),
        ) {
            text("Copy")
        }
        text("Source: $source Copy: $copy")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.PendingEffectRemovalApp(probe: EffectLogProbe) {
    var show by state { false }

    column {
        button(
            onClick = event { show = !show },
            semantics = Semantics(role = Role.Button, testTag = "toggle"),
        ) {
            text("Toggle")
        }
        if (show) {
            PendingEffectChild(probe)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.PendingEffectChild(probe: EffectLogProbe) {
    launchEffect {
        probe.log += "init"
        awaitDispose { probe.log += "cancel" }
    }
    text("Child", semantics = Semantics(testTag = "pending-child"))
}

@UiComponent(skippable = false)
private fun ComponentScope.WatchBatchingApp(probe: EffectLogProbe) {
    var count by state { 0 }
    val doubled = derived { count * 2 }

    watch(source = { count to doubled.value }) { value ->
        probe.log += "A:${value.first}"
    }
    watch(source = { count }) { value ->
        probe.log += "B:$value"
    }
    column {
        button(
            onClick = event {
                count += 1
                count += 1
            },
            semantics = Semantics(role = Role.Button, testTag = "double-bump"),
        ) {
            text("Bump")
        }
        text("Count: $count")
    }
}

private suspend fun waitForLog(
    probe: EffectLogProbe,
    expected: List<String>,
    root: TestRoot? = null,
    phase: String? = null,
) {
    try {
        waitUntil { probe.log.toList() == expected }
    } catch (timeout: TimeoutCancellationException) {
        if (root == null) {
            throw timeout
        }
        val actual = probe.log.toList()
        val recentJournal = root.journal()
            .takeLast(40)
            .joinToString(separator = "\n") { entry ->
                "${entry.sequence} ${entry.kind}: ${entry.message} ${entry.attributes}"
            }
        throw AssertionError(
            buildString {
                appendLine("Timed out waiting for effect log during $phase.")
                appendLine("Expected: $expected")
                appendLine("Actual:   $actual")
                appendLine("Committed renders: ${root.committedRenderCount()}")
                appendLine("Tree: ${root.tree()}")
                appendLine("Recent journal:")
                append(recentJournal)
            },
        )
    }
    assertEquals(expected, probe.log.toList())
}

private suspend fun waitForLog(probe: WatchBranchProbe, expected: List<String>) {
    waitUntil { probe.log.toList() == expected }
    assertEquals(expected, probe.log.toList())
}

private suspend fun waitForLog(probe: ExitAbandonmentProbe, expected: List<String>) {
    waitUntil { probe.log.toList() == expected }
    assertEquals(expected, probe.log.toList())
}

private suspend fun TestRoot.settle() {
    withContext(Dispatchers.Default) {
        withTimeout(2_000) {
            awaitIdle()
        }
    }
}

private suspend fun waitUntil(predicate: () -> Boolean) {
    withContext(Dispatchers.Default) {
        withTimeout(2_000) {
            while (!predicate()) {
                delay(10)
            }
        }
    }
}

private suspend fun delayBriefly() {
    withContext(Dispatchers.Default) {
        delay(50)
    }
}

private fun TestRoot.committedRenderCount(): Int =
    journal().count { entry -> entry.kind == JournalKind.RenderCommitted }
