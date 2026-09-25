@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.appkit

import io.heapy.kinetica.*
import kotlinx.cinterop.useContents
import platform.AppKit.*
import platform.Foundation.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.test.*
import kotlin.time.TimeSource

class AppKitOutlineTableTest {
    private val columns = listOf(OutlineColumn("name", "Process", 280.0), OutlineColumn("cpu", "CPU", numeric = true))
    private fun row(key: String, value: String = "1.0%", children: List<OutlineRow> = emptyList()) =
        OutlineRow(key, mapOf("name" to OutlineCell(key), "cpu" to OutlineCell(value)), children)

    private fun window(): NSWindow {
        NSApplication.sharedApplication()
        return NSWindow(NSMakeRect(0.0, 0.0, 800.0, 400.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false).apply {
            setReleasedWhenClosed(false)
        }
    }

    @Test
    fun refreshRetainsNativeItemsExpansionSelectionAndColumnWidthWithoutEvents() {
        val events = mutableListOf<OutlineTableEvent>()
        val table = AppKitOutlineTable(events::add)
        val window = window()
        window.contentView = table
        try {
            val initial = OutlineTableModel(columns, listOf(row("parent", children = listOf(row("child")))), setOf("parent"), "child")
            table.update(initial)
            val child = table.controller.items.getValue("child")
            assertEquals(2, table.outline.numberOfRows)
            assertEquals(1, table.outline.selectedRow)
            (table.outline.tableColumns.first() as NSTableColumn).setWidth(337.0)
            val reloads = table.controller.structureReloadCount
            table.update(initial.copy(rows = listOf(row("parent", children = listOf(row("child", "9.0%"))))))
            assertSame(child, table.controller.items.getValue("child"))
            assertEquals(reloads, table.controller.structureReloadCount)
            assertTrue(table.outline.isItemExpanded(table.controller.items.getValue("parent")))
            assertEquals(1, table.outline.selectedRow)
            assertEquals(337.0, (table.outline.tableColumns.first() as NSTableColumn).width)
            val cell = table.outline.viewAtColumn(1, 1, true) as NSTableCellView
            assertEquals("9.0%", cell.textField?.stringValue)
            assertTrue(events.isEmpty(), "Applying a controlled model must not emit user events")
        } finally { table.dispose(); window.close() }
    }

    @Test
    fun nativeSelectionExpansionAndHeaderSortEmitTypedEvents() {
        val events = mutableListOf<OutlineTableEvent>()
        val table = AppKitOutlineTable(events::add)
        val window = window()
        window.contentView = table
        try {
            table.update(OutlineTableModel(columns, listOf(row("parent", children = listOf(row("child"))))))
            table.outline.expandItem(table.controller.items.getValue("parent"))
            assertContains(events, OutlineTableEvent.ExpansionChanged("parent", true))
            table.outline.selectRowIndexes(NSIndexSet.indexSetWithIndex(1u), false)
            assertContains(events, OutlineTableEvent.SelectionChanged("child"))
            table.outline.setSortDescriptors(listOf(NSSortDescriptor("cpu", false)))
            assertContains(events, OutlineTableEvent.SortChanged(OutlineSort("cpu", false)))
        } finally { table.dispose(); window.close() }
    }

    @Test
    fun changingColumnPresetsRetainsWidthsWithoutDuplicatingTheDisclosureColumn() {
        val table = AppKitOutlineTable { }
        val window = window()
        window.contentView = table
        try {
            val initial = OutlineTableModel(columns, listOf(row("one")))
            table.update(initial)
            (table.outline.tableColumns.first() as NSTableColumn).setWidth(337.0)
            val alternate = listOf(columns.first(), OutlineColumn("memory", "Memory", numeric = true))
            repeat(3) {
                table.update(initial.copy(columns = alternate))
                assertEquals(listOf("name", "memory"), table.outline.tableColumns.map { (it as NSTableColumn).identifier })
                table.update(initial)
                assertEquals(listOf("name", "cpu"), table.outline.tableColumns.map { (it as NSTableColumn).identifier })
                assertEquals(337.0, (table.outline.tableColumns.first() as NSTableColumn).width)
            }
        } finally { table.dispose(); window.close() }
    }

    @Test
    fun thousandRowsUseViewportCellsAndKeepScrollAnchorAcrossReorder() {
        val table = AppKitOutlineTable { }
        val window = window()
        window.contentView = table
        try {
            val model = OutlineTableModel(columns, (0 until 1_000).map { row("process-$it") })
            table.update(model)
            window.layoutIfNeeded()
            table.outline.layoutSubtreeIfNeeded()
            table.outline.scrollRowToVisible(500)
            val origin = table.contentView().bounds.useContents { origin.y }
            val top = table.outline.rowAtPoint(NSMakePoint(0.0, origin))
            val anchor = (table.outline.itemAtRow(top) as OutlineItem).row.key
            val start = TimeSource.Monotonic.markNow()
            repeat(30) { tick -> table.update(model.copy(rows = model.rows.map { it.copy(cells = it.cells + ("cpu" to OutlineCell("$tick.0%"))) })) }
            val elapsed = start.elapsedNow()
            assertEquals(1, table.controller.structureReloadCount)
            table.update(model.copy(rows = model.rows.reversed()))
            val after = table.contentView().bounds.useContents { this.origin.y }
            val afterRow = table.outline.rowAtPoint(NSMakePoint(0.0, after))
            assertEquals(anchor, (table.outline.itemAtRow(afterRow) as OutlineItem).row.key)
            assertTrue(table.controller.createdCellCount > 0)
            assertTrue(table.controller.createdCellCount < 500, "Expected viewport reuse, created ${table.controller.createdCellCount} cells")
            println("outline benchmark: 1000 rows, 30 updates in $elapsed, ${table.controller.createdCellCount} native cells")
        } finally { table.dispose(); window.close() }
    }

    @Test
    fun removingSubmitDoesNotDisconnectInputAndDisposalDropsQueuedOutlineEvents() {
        val submit = store(true)
        val received = mutableListOf<String>()
        val window = window()
        val renderer = renderAppKitApp(window.contentView!!) { BindingProbe(submit, received::add) }
        try {
            fun find(view: NSView): NSTextField? =
                if (view.identifier == "input") view as NSTextField else view.subviews.filterIsInstance<NSView>().firstNotNullOfOrNull(::find)
            val field = requireNotNull(find(window.contentView!!))
            submit.value = false
            renderer.renderUntilSettled()
            field.setStringValue("still connected")
            field.delegate!!.controlTextDidChange(NSNotification.notificationWithName(NSControlTextDidChangeNotification, field))
            assertEquals(listOf("still connected"), received)
        } finally { renderer.dispose(); window.close() }

        val runtime = KineticaRuntime()
        var renders = 0
        val dispatcher = AppKitEventDispatcher(runtime) { renders++ }
        dispatcher.dispatch("removed-event", "value")
        drainMainQueue()
        assertEquals(1, renders)
        dispatcher.dispatch("removed-event", "value")
        dispatcher.reset()
        drainMainQueue()
        assertEquals(1, renders)
        runtime.dispose()
    }

    @Test
    fun malformedModelsFailBeforeMutatingTheControl() {
        val table = AppKitOutlineTable { }
        val window = window()
        window.contentView = table
        try {
            val model = OutlineTableModel(columns, listOf(row("one")))
            table.update(model)
            assertFailsWith<IllegalArgumentException> { table.update(model.copy(rows = listOf(row("one"), row("one")))) }
            assertEquals(1, table.outline.numberOfRows)
        } finally { table.dispose(); window.close() }
    }

    private fun drainMainQueue() {
        var drained = false
        dispatch_async(dispatch_get_main_queue()) { drained = true }
        val deadline = TimeSource.Monotonic.markNow()
        while (!drained && deadline.elapsedNow().inWholeMilliseconds < 1_000) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        }
        assertTrue(drained, "The main queue must be serviced before checking native events")
    }
}

@UiComponent
private fun ComponentScope.BindingProbe(submit: Cell<Boolean>, onInput: (String) -> Unit) {
    textInput("", onInput = onInput, onSubmit = if (submit.value) ({ }) else null, semantics = Semantics(testTag = "input"))
}
