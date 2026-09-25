package io.heapy.kinetica.samples.harmon

import io.heapy.kinetica.appkit.OutlineSort
import kotlin.test.*

class ProcessModelTest {
    private fun process(id: Int, cpu: Double?, children: List<ProcessRecord> = emptyList(), name: String = "p$id") =
        ProcessRecord("$id:start", id, name, mapOf(Metric.Cpu to Measurement(cpu)), children)

    @Test
    fun selfAndTotalSortIndependentlyAndMissingReadingsStayLastInBothDirections() {
        val snapshot = ProcessSnapshot(0, listOf(process(1, 1.0, listOf(process(11, 20.0))), process(2, 10.0), process(3, null)))
        val state = MonitorState(snapshot, expanded = emptySet())
        assertEquals(listOf("2:start", "1:start", "3:start"), projectMonitor(state.copy(sort = OutlineSort("Cpu.self"))).table.rows.map { it.key })
        assertEquals(listOf("1:start", "2:start", "3:start"), projectMonitor(state.copy(sort = OutlineSort("Cpu.total"))).table.rows.map { it.key })
        assertEquals(listOf("1:start", "2:start", "3:start"), projectMonitor(state.copy(sort = OutlineSort("Cpu.self", true))).table.rows.map { it.key })
    }

    @Test
    fun searchKeepsAncestorsAndMatchedSubtreesWithoutChangingTotals() {
        val snapshot = ProcessSnapshot(0, listOf(process(1, 1.0, listOf(process(11, 20.0, name = "needle"), process(12, 30.0)))))
        val projected = projectMonitor(MonitorState(snapshot, query = "needle"))
        assertEquals(listOf("11:start"), projected.table.rows.single().children.map { it.key })
        assertEquals("51.0%", projected.table.rows.single().cells.getValue("Cpu.total").text)
        assertTrue("1:start" in projected.table.expandedKeys)
        assertEquals(2, projected.matchingCount)
        assertEquals(3, projectMonitor(MonitorState(snapshot, query = "p1")).matchingCount)
        assertEquals(0, projectMonitor(MonitorState(snapshot, query = "nothing")).matchingCount)
    }

    @Test
    fun searchExpansionRespectsCollapseAndResetsForANewQuery() {
        val searching = MonitorState(expanded = emptySet()).withQuery("Safari")
        val key = projectMonitor(searching).table.rows.single().key
        assertContains(projectMonitor(searching).table.expandedKeys, key)
        val collapsed = searching.withExpansion(key, false)
        assertFalse(key in projectMonitor(collapsed.nextSample()).table.expandedKeys)
        assertContains(projectMonitor(collapsed.withExpansion(key, true)).table.expandedKeys, key)
        assertTrue(projectMonitor(searching.withAllExpanded(false)).table.expandedKeys.isEmpty())
        assertContains(projectMonitor(collapsed.withQuery("Safari Helper")).table.expandedKeys, key)
    }

    @Test
    fun partialTotalsAreDistinguishedFromZeroAndUnavailable() {
        val snapshot = ProcessSnapshot(0, listOf(process(1, 0.0, listOf(process(11, null))), process(2, null)))
        val rows = projectMonitor(MonitorState(snapshot)).table.rows.associateBy { it.key }
        assertEquals("0.0%", rows.getValue("1:start").cells.getValue("Cpu.self").text)
        assertEquals("≥ 0.0%", rows.getValue("1:start").cells.getValue("Cpu.total").text)
        assertEquals("—", rows.getValue("2:start").cells.getValue("Cpu.total").text)
    }

    @Test
    fun snapshotRefreshPreservesUiStateAndClearsSelectionWhenProcessIdentityChanges() {
        val state = MonitorState(fixtureSnapshot(4), query = "Safari", selected = "121:start-0", sort = OutlineSort("pid", true), frozen = true)
        val next = state.nextSample()
        assertEquals("Safari", next.query)
        assertEquals(state.sort, next.sort)
        assertEquals(state.expanded, next.expanded)
        assertTrue(next.frozen)
        assertNull(next.selected)
        assertTrue(next.snapshot.allProcesses().any { it.key == "121:start-1" })
        assertEquals("120:start-0", state.copy(selected = "120:start-0").nextSample().selected)
    }

    @Test
    fun presetsResetSortToAVisibleColumnAndLargeFixturesKeepUniqueKeys() {
        val state = MonitorState()
        assertEquals(1_000, state.snapshot.allProcesses().size)
        for (preset in MetricPreset.entries) {
            val projected = projectMonitor(state.withPreset(preset))
            projected.table.validate()
            assertTrue(projected.table.columns.any { it.id == projected.table.sort?.column })
        }
        assertEquals(999, fixtureSnapshot(3).allProcesses().size)
    }
}
