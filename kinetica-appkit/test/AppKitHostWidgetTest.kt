@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.appkit

import io.heapy.kinetica.*
import io.heapy.kinetica.render.*
import platform.AppKit.*
import platform.Foundation.NSMakeRect
import kotlin.test.*

class AppKitHostWidgetTest {
    @Test fun customHostRetainsItsChildrenForUpdatesAndReleasesEachInstanceOnce() {
        NSApplication.sharedApplication()
        val window = NSWindow(NSMakeRect(0.0, 0.0, 400.0, 200.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false)
        var shown = true
        var key = "first"
        var value = "one"
        var created = 0
        var disposed = 0
        val fields = mutableListOf<NSTextField>()
        val factory = HostWidgetFactory<NSView> {
            created++
            val field = NSTextField(NSMakeRect(0.0, 0.0, 100.0, 24.0))
            fields += field
            object : HostWidget<NSView> {
                override val view = NSView().apply { addSubview(field) }
                override fun update(node: HostNode) { field.stringValue = node.props.getValue("value") }
                override fun requestFocus(): Boolean = view.window?.makeFirstResponder(field) == true
                override fun dispose() { disposed++ }
            }
        }
        val app = renderAppKitApp(window.contentView!!, hostWidgets = mapOf("custom" to factory)) {
            column {
                if (shown) host("custom", props = mapOf("value" to value), key = key,
                    semantics = Semantics(testTag = "custom"))
            }
        }
        try {
            val first = fields.single()
            val container = first.superview
            value = "two"
            app.render()
            assertEquals(1, created)
            assertEquals(0, disposed)
            assertEquals(container, first.superview)
            assertEquals(listOf(first), container!!.subviews)
            assertEquals("two", first.stringValue)
            assertTrue(app.focus("custom"))
            assertEquals(first.currentEditor(), window.firstResponder)

            key = "second"
            app.render()
            assertEquals(2, created)
            assertEquals(1, disposed)
            assertEquals("two", fields.last().stringValue)
            shown = false
            app.render()
            assertEquals(2, disposed)
            assertFalse(app.focus("custom"))
            app.dispose()
            app.dispose()
            assertEquals(2, disposed)
        } finally { app.dispose(); window.close() }
    }

    @Test fun customHostRejectsFrameworkChildrenBeforeCreatingTheWidget() {
        NSApplication.sharedApplication()
        var created = 0
        val runtime = KineticaRuntime()
        val adapter = AppKitHostAdapter(AppKitEventDispatcher(runtime) {}, mapOf("custom" to HostWidgetFactory {
            created++
            error("A non-leaf host must not reach the factory")
        }))
        try {
            assertFailsWith<IllegalArgumentException> {
                adapter.createHost(HostNode("custom", children = listOf(TextNode("unexpected child"))))
            }
            assertEquals(0, created)
        } finally { runtime.dispose() }
    }
}
