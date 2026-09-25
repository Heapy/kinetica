@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.samples.harmon

import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.appkit.renderAppKitApp
import platform.AppKit.*
import platform.Foundation.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.test.*
import kotlin.time.TimeSource

class MonitorRenderTest {
    @Test
    fun liveModeRendersWireDataWithoutSampleControls() {
        NSApplication.sharedApplication()
        val window = NSWindow(NSMakeRect(0.0, 0.0, 1120.0, 720.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false)
        val source = object : LiveSource {
            override suspend fun fetch(): LivePayload = error("Frozen mode must not fetch")
            override fun close() { }
        }
        val session = MonitorSession(source)
        session.setFrozen(true)
        session.state.value = session.state.value.withSnapshot(requireNotNull(decodeLivePayload(wireFixture()).snapshot()))
        val renderer = renderAppKitApp(window.contentView!!, KineticaRuntime(debug = false)) { HarmonMonitor(session) }
        try {
            fun views(view: NSView): List<NSView> = listOf(view) + view.subviews.filterIsInstance<NSView>().flatMap(::views)
            val controls = views(window.contentView!!)
            val buttons = controls.filterIsInstance<NSButton>().associateBy { it.title }
            assertTrue("Reconnect" in buttons)
            assertFalse(buttons.getValue("Reconnect").enabled)
            assertTrue(listOf("Next Sample", "Ready", "Warming", "Stale").none { it in buttons })
            assertTrue("Collapse All" in buttons)
            val table = controls.filterIsInstance<NSOutlineView>().single()
            assertEquals(1, table.numberOfRows)
            val total = table.viewAtColumn(3, 0, true) as NSTableCellView
            assertEquals("≥ 99.0%", total.textField!!.stringValue)
            assertTrue(controls.filterIsInstance<NSTextField>().any { it.stringValue.contains("18446744073709551615") })
            drainMainQueue()
        } finally { renderer.dispose(); window.close() }
    }

    @Test
    fun fullKineticaPipelineRendersFiltersSelectsAndUpdatesThousandProcesses() {
        NSApplication.sharedApplication()
        val window = NSWindow(NSMakeRect(0.0, 0.0, 1120.0, 720.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false)
        val session = MonitorSession()
        session.state.value = session.state.value.copy(frozen = true)
        val renderer = renderAppKitApp(window.contentView!!, KineticaRuntime(debug = false)) { HarmonMonitor(session) }
        try {
            fun outline(view: NSView): NSOutlineView? = when (view) {
                is NSOutlineView -> view
                else -> view.subviews.filterIsInstance<NSView>().firstNotNullOfOrNull(::outline)
            }
            val table = requireNotNull(outline(window.contentView!!))
            fun button(view: NSView, title: String): NSButton? =
                if (view is NSButton && view.title == title) view else view.subviews.filterIsInstance<NSView>().firstNotNullOfOrNull { button(it, title) }
            window.layoutIfNeeded()
            assertEquals(1_000, table.numberOfRows)
            session.state.value = session.state.value.withPreset(MetricPreset.Energy)
            renderer.renderUntilSettled()
            assertTrue(requireNotNull(button(window.contentView!!, "Overview")).enabled)
            assertFalse(requireNotNull(button(window.contentView!!, "Energy")).enabled)
            session.state.value = session.state.value.withPreset(MetricPreset.Overview)
            session.state.value = session.state.value.copy(snapshot = session.state.value.snapshot.copy(status = SampleStatus.Stale))
            renderer.renderUntilSettled()
            assertTrue(requireNotNull(button(window.contentView!!, "Ready")).enabled)
            assertFalse(requireNotNull(button(window.contentView!!, "Stale")).enabled)
            session.state.value = session.state.value.copy(query = "Safari")
            renderer.renderUntilSettled()
            assertEquals(50, table.numberOfRows)
            drainMainQueue()
            table.selectRowIndexes(NSIndexSet.indexSetWithIndex(1u), false)
            drainMainQueue()
            assertNotNull(session.state.value.selected)
            val selected = session.state.value.selected
            session.state.value = session.state.value.nextSample()
            renderer.renderUntilSettled()
            assertEquals(selected, session.state.value.selected)
            assertTrue(table.selectedRow >= 0)
            session.state.value = session.state.value.copy(query = "")
            renderer.renderUntilSettled()
            val start = TimeSource.Monotonic.markNow()
            repeat(30) {
                session.state.value = session.state.value.nextSample()
                renderer.renderUntilSettled()
                window.layoutIfNeeded()
            }
            println("Harmon pipeline benchmark: snapshot + projection + JSON + AppKit, 30 updates in ${start.elapsedNow()}")
            assertTrue(table.numberOfRows in 999..1_000)
        } finally {
            renderer.dispose()
            window.close()
        }
    }

    private fun drainMainQueue() {
        assertTrue(NSThread.isMainThread)
        var drained = false
        dispatch_async(dispatch_get_main_queue()) { drained = true }
        val deadline = TimeSource.Monotonic.markNow()
        while (!drained && deadline.elapsedNow().inWholeMilliseconds < 1_000) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        }
        assertTrue(drained, "The main queue must be serviced before checking native events")
    }
}
