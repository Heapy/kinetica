package io.heapy.kinetica.testing

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.JournalKind
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.button
import io.heapy.kinetica.column
import io.heapy.kinetica.derived
import io.heapy.kinetica.event
import io.heapy.kinetica.state
import io.heapy.kinetica.store
import io.heapy.kinetica.text
import io.heapy.kinetica.watch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchOrderingTest {
    /**
     * KSND-115 (sources: RCT-515, SOL-007, VUE-024, INF-119).
     * Certifies per-source-write propagation waves inside one event plus one render commit.
     */
    @Test
    fun multipleWritesInOneEventRunPerWriteWavesAndCommitOnce() {
        val probe = MultiWriteProbe()
        val root = KineticaTest.render {
            MultiWriteApp(probe)
        }

        try {
            probe.recomputes = 0
            val beforeCommits = root.journal().count { it.kind == JournalKind.RenderCommitted }

            root.click(hasTestTag("multi-write"))

            assertEquals(beforeCommits + 1, root.journal().count { it.kind == JournalKind.RenderCommitted })
            assertEquals(
                "a=2,b=9,total=11",
                (root.node(hasText("a=2,b=9,total=11")).node as TextNode).value,
            )
            // Three changed source writes run three propagation waves; render refreshes the derived definition once.
            assertEquals(4, probe.recomputes)
        } finally {
            root.dispose()
        }
    }

    /**
     * KSND-116 (sources: SVL-005, SOL-007, VUE-062).
     * Certifies write-then-revert remains wave-consistent and commits once.
     */
    @Test
    fun writeThenRevertKeepsPublicObserversConsistentAndCommitsOnce() = runTest {
        val probe = RevertWriteProbe()
        val root = KineticaTest.render {
            RevertWriteApp(probe)
        }

        suspend fun settle() = withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                root.awaitIdle()
            }
        }

        try {
            settle()
            val beforeDom = root.htmlSnapshot()
            val beforeCommits = root.journal().count { it.kind == JournalKind.RenderCommitted }
            probe.observedStates.clear()

            root.click(hasTestTag("revert-write"))
            settle()

            assertEquals(beforeDom, root.htmlSnapshot())
            assertEquals(beforeCommits + 1, root.journal().count { it.kind == JournalKind.RenderCommitted })
            assertEquals(
                emptyList(),
                probe.observedStates.filterNot { state -> state == "n=1,label=one" || state == "n=0,label=zero" },
            )
        } finally {
            root.dispose()
        }
    }

    /**
     * KSND-117 (sources: SVL-006, VUE-027, SOL-021).
     * Certifies two source writes run two propagation waves while the event commits once.
     */
    @Test
    fun twoCellsFeedingOneDerivedRunTwoWavesAndCommitOnce() {
        val probe = TwoCellDerivedProbe()
        val root = KineticaTest.render {
            TwoCellDerivedApp(probe)
        }

        try {
            probe.recomputes = 0
            val beforeFirstCommits = root.journal().count { it.kind == JournalKind.RenderCommitted }

            root.click(hasTestTag("advance-sum"))

            assertEquals(beforeFirstCommits + 1, root.journal().count { it.kind == JournalKind.RenderCommitted })
            assertEquals(
                "sum=14",
                (root.node(hasText("sum=14")).node as TextNode).value,
            )
            // Two changed source writes run two propagation waves; render refreshes the derived definition once.
            assertEquals(3, probe.recomputes)

            probe.recomputes = 0
            val beforeSecondCommits = root.journal().count { it.kind == JournalKind.RenderCommitted }

            root.click(hasTestTag("advance-sum"))

            assertEquals(beforeSecondCommits + 1, root.journal().count { it.kind == JournalKind.RenderCommitted })
            assertEquals(
                "sum=25",
                (root.node(hasText("sum=25")).node as TextNode).value,
            )
            assertEquals(3, probe.recomputes)
        } finally {
            root.dispose()
        }
    }

    /**
     * KSND-118 (sources: SVL-011, SVL-019b, VUE-036).
     */
    @Test
    fun writeReadDerivedWriteAgainKeepsFreshMidReadAndFinalCommit() {
        val probe = ReadBetweenWritesProbe()
        val root = KineticaTest.render {
            ReadBetweenWritesApp(probe)
        }

        try {
            val beforeCommits = root.journal().count { it.kind == JournalKind.RenderCommitted }

            root.click(hasTestTag("read-between-writes"))

            assertEquals(listOf(0), probe.midReads)
            assertEquals(
                "z=0",
                (root.node(hasText("z=0")).node as TextNode).value,
            )
            assertEquals(beforeCommits + 1, root.journal().count { it.kind == JournalKind.RenderCommitted })
        } finally {
            root.dispose()
        }
    }

    /**
     * KSND-119 (sources: RCT-516, RCT-524, INF-126).
     */
    @Test
    fun backToBackDispatchesEachCommitOnceInOrder() {
        val root = KineticaTest.render {
            BackToBackDispatchApp()
        }

        try {
            val beforeCommits = root.journal().count { it.kind == JournalKind.RenderCommitted }
            val beforeJournalSize = root.journal().size

            root.click(hasTestTag("dispatch-a"))
            root.click(hasTestTag("dispatch-b"))

            val newEntries = root.journal().drop(beforeJournalSize)
            val commitIndexes = newEntries.withIndex()
                .filter { it.value.kind == JournalKind.RenderCommitted }
                .map { it.index }
            val aWriteIndex = newEntries.indexOfFirst {
                it.kind == JournalKind.CellWrite && it.attributes["value"] == "A"
            }
            val bWriteIndex = newEntries.indexOfFirst {
                it.kind == JournalKind.CellWrite && it.attributes["value"] == "B"
            }

            assertEquals(beforeCommits + 2, root.journal().count { it.kind == JournalKind.RenderCommitted })
            assertEquals(
                "first=A,second=B",
                (root.node(hasText("first=A,second=B")).node as TextNode).value,
            )
            assertEquals(2, commitIndexes.size)
            assertTrue(aWriteIndex >= 0)
            assertTrue(bWriteIndex >= 0)
            assertTrue(aWriteIndex < commitIndexes[0])
            assertTrue(commitIndexes[0] < bWriteIndex)
            assertTrue(bWriteIndex < commitIndexes[1])
        } finally {
            root.dispose()
        }
    }

    /**
     * KSND-120 (sources: RCT-523, KineticaTestSmoke).
     */
    @Test
    fun scopeFreeStoreWritesCoalesceIntoOneCellWriteRender() = runTest {
        val probe = ScopeFreeStoreProbe()
        val root = KineticaTest.render {
            ScopeFreeStoreApp(probe)
        }

        suspend fun settle() = withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                root.awaitIdle()
            }
        }

        try {
            val beforeCommits = root.journal().count { it.kind == JournalKind.RenderCommitted }

            probe.store.value = 1
            probe.store.value = 2
            probe.store.value = 3
            settle()

            assertEquals(beforeCommits + 1, root.journal().count { it.kind == JournalKind.RenderCommitted })
            assertEquals("cell write", root.journal().last { it.kind == JournalKind.RenderCommitted }.attributes["cause"])
            assertEquals(
                "store=3",
                (root.node(hasText("store=3")).node as TextNode).value,
            )
        } finally {
            root.dispose()
        }
    }

    /**
     * KSND-121 (sources: SVL-089, VUE-009, SOL-008).
     */
    @Test
    fun convergentSelfWriteEffectReachesFixpointWithinAwaitIdle() = runTest {
        val root = KineticaTest.render {
            ConvergentSelfWriteEffectApp()
        }

        suspend fun settle() = withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                root.awaitIdle()
            }
        }

        try {
            settle()
            assertEquals(
                "power=10",
                (root.node(hasText("power=10")).node as TextNode).value,
            )

            val beforeCommits = root.journal().count { it.kind == JournalKind.RenderCommitted }
            root.click(hasTestTag("start-power"))
            settle()

            val afterFirstIdle = root.journal().count { it.kind == JournalKind.RenderCommitted }
            assertEquals(
                "power=10",
                (root.node(hasText("power=10")).node as TextNode).value,
            )
            assertEquals(beforeCommits + 10, afterFirstIdle)

            settle()

            assertEquals(afterFirstIdle, root.journal().count { it.kind == JournalKind.RenderCommitted })
        } finally {
            root.dispose()
        }
    }

    /**
     * KSND-122 (sources: VUE-059, VUE-080, VUE-062).
     */
    @Test
    fun watchWritingDifferentCellSettlesWithoutRetriggeringItself() = runTest {
        val probe = WatchDifferentCellProbe()
        val root = KineticaTest.render {
            WatchDifferentCellApp(probe)
        }

        suspend fun settle() = withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                root.awaitIdle()
            }
        }

        try {
            settle()
            probe.fires = 0
            val beforeFirstClick = root.journal().count { it.kind == JournalKind.RenderCommitted }

            root.click(hasTestTag("write-input"))
            settle()

            assertEquals(1, probe.fires)
            assertEquals(beforeFirstClick + 2, root.journal().count { it.kind == JournalKind.RenderCommitted })
            assertEquals(
                "input=9,output=5",
                (root.node(hasText("input=9,output=5")).node as TextNode).value,
            )

            val beforeSecondClick = root.journal().count { it.kind == JournalKind.RenderCommitted }
            root.click(hasTestTag("write-input"))
            settle()

            assertEquals(1, probe.fires)
            assertEquals(beforeSecondClick + 1, root.journal().count { it.kind == JournalKind.RenderCommitted })
            assertEquals(
                "input=9,output=5",
                (root.node(hasText("input=9,output=5")).node as TextNode).value,
            )
        } finally {
            root.dispose()
        }
    }
}

private class MultiWriteProbe {
    var recomputes = 0
}

private class RevertWriteProbe {
    val observedStates = mutableListOf<String>()
}

private class TwoCellDerivedProbe {
    var recomputes = 0
}

private class ReadBetweenWritesProbe {
    val midReads = mutableListOf<Int>()
}

private class ScopeFreeStoreProbe {
    val store = store(0)
}

private class WatchDifferentCellProbe {
    var fires = 0
}

@UiComponent(skippable = false)
private fun ComponentScope.MultiWriteApp(probe: MultiWriteProbe) {
    var a by state { 0 }
    var b by state { 0 }
    val total = derived {
        probe.recomputes += 1
        a + b
    }

    column {
        text("a=$a,b=$b,total=${total.value}")
        button(
            onClick = event {
                a = 1
                a = 2
                b = 9
            },
            semantics = Semantics(role = Role.Button, testTag = "multi-write"),
        ) {
            text("Write")
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.RevertWriteApp(probe: RevertWriteProbe) {
    var n by state { 0 }
    val label = derived {
        if (n == 0) "zero" else "one"
    }

    watch(source = { "n=$n,label=${label.value}" }) { state ->
        probe.observedStates += state
    }

    column {
        text("n=$n,label=${label.value}")
        button(
            onClick = event {
                n = 1
                n = 0
            },
            semantics = Semantics(role = Role.Button, testTag = "revert-write"),
        ) {
            text("Revert")
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.TwoCellDerivedApp(probe: TwoCellDerivedProbe) {
    var a by state { 1 }
    var b by state { 2 }
    val sum = derived {
        probe.recomputes += 1
        a + b
    }

    column {
        text("sum=${sum.value}")
        button(
            onClick = event {
                a += 1
                b += 10
            },
            semantics = Semantics(role = Role.Button, testTag = "advance-sum"),
        ) {
            text("Advance")
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.ReadBetweenWritesApp(probe: ReadBetweenWritesProbe) {
    var x by state { 1 }
    var y by state { 1 }
    val z = derived {
        x * y
    }

    column {
        text("z=${z.value}")
        button(
            onClick = event {
                x = 0
                val mid = z.value
                probe.midReads += mid
                y = 0
            },
            semantics = Semantics(role = Role.Button, testTag = "read-between-writes"),
        ) {
            text("Read")
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.BackToBackDispatchApp() {
    var first by state { "off" }
    var second by state { "off" }

    column {
        text("first=$first,second=$second")
        button(
            onClick = event { first = "A" },
            semantics = Semantics(role = Role.Button, testTag = "dispatch-a"),
        ) {
            text("A")
        }
        button(
            onClick = event { second = "B" },
            semantics = Semantics(role = Role.Button, testTag = "dispatch-b"),
        ) {
            text("B")
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.ScopeFreeStoreApp(probe: ScopeFreeStoreProbe) {
    text("store=${probe.store.value}")
}

@UiComponent(skippable = false)
private fun ComponentScope.ConvergentSelfWriteEffectApp() {
    val power = state { 10 }

    watch(source = { power.value }) { value ->
        if (value < 10) {
            power.value = value + 1
        }
    }
    column {
        text("power=${power.value}")
        button(
            onClick = event { power.value = 1 },
            semantics = Semantics(role = Role.Button, testTag = "start-power"),
        ) {
            text("Start")
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.WatchDifferentCellApp(probe: WatchDifferentCellProbe) {
    var input by state { 0 }
    var output by state { 0 }

    watch(source = { input }) { value ->
        probe.fires += 1
        output = value.coerceIn(0, 5)
    }
    column {
        text("input=$input,output=$output")
        button(
            onClick = event { input = 9 },
            semantics = Semantics(role = Role.Button, testTag = "write-input"),
        ) {
            text("Write")
        }
    }
}
