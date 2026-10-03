@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.appkit

import io.heapy.kinetica.*
import io.heapy.kinetica.application.*
import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*
import kotlin.time.TimeSource

class AppKitApplicationTest {
    @Test fun closingABackgroundTabTargetsItsHandleAndKeepsTheSelectedTab() {
        val app = AppKitApplication("Test", nativeApplication = native(), quitAfterLastWindow = false)
        app.install()
        try {
            val first = app.openWindow(ApplicationWindow("first", "First", tabGroup = "test")) { text("first") }
            val second = app.openWindow(ApplicationWindow("second", "Second", tabGroup = "test"), tabOf = first.id) { text("second") }
            await(app.nativeApplication) { app.activeWindow == second }
            first.close()
            assertEquals(listOf(second), app.windows)
            assertFalse(first.nativeWindow.visible)
            assertTrue(second.nativeWindow.visible)
            assertNotNull(second.renderer)
        } finally { app.dispose() }
    }

    @Test fun aComponentActionCanCloseItsOwnWindowWithoutRenderingAfterDisposal() {
        val app = AppKitApplication("Test", nativeApplication = native(), quitAfterLastWindow = false)
        var released = 0
        app.install()
        try {
            val window = app.openWindow(ApplicationWindow("self-closing", "Close"),
                callbacks = WindowCallbacks(release = { released++; it() })) {
                CloseWindowButton { app.window("self-closing")?.close() }
            }
            val button = window.nativeWindow.contentView!!.subviews.single() as NSButton
            button.performClick(null)
            assertTrue(app.windows.isEmpty())
            assertNull(window.renderer)
            assertEquals(1, released)
            assertNull(button.target)
        } finally { app.dispose() }
    }

    private fun native(): NSApplication = NSApplication.sharedApplication().also {
        it.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        it.finishLaunching(); it.activateIgnoringOtherApps(true)
    }

    @Test fun nativeMenusRouteToSelectedTabsAndRetainTheirRenderersAndFocus() {
        val native = native()
        val app = AppKitApplication("Test", nativeApplication = native, quitAfterLastWindow = false)
        val invoked = mutableListOf<String?>()
        val value = store("initial")
        app.commands.register(ApplicationCommand("action", "Action", listOf(KeyShortcut("g")), windowRequired = true,
            action = { invoked += it.windowId }))
        app.commands.register(ApplicationCommand("previous", "Previous Tab", listOf(KeyShortcut("[", setOf(KeyModifier.PRIMARY, KeyModifier.SHIFT))),
            windowRequired = true) { app.nextTab(backwards = true) })
        app.setMenus(listOf(ApplicationMenu("Test", listOf(MenuItem.Command("action"), MenuItem.Command("previous")))))
        app.install()
        try {
            val first = app.openWindow(ApplicationWindow("one", "One", tabGroup = "tests", initialFocus = "input")) {
                ApplicationInput(value)
            }
            val renderer = first.renderer
            val second = app.openWindow(ApplicationWindow("two", "Two", tabGroup = "tests"), tabOf = first.id) { text("second") }
            await(native) { app.activeWindow == second }
            assertEquals(2, second.nativeWindow.tabGroup!!.windows.size)
            assertTrue(app.menu.performKeyEquivalent(key(second.nativeWindow, "g")))
            assertEquals(listOf<String?>("two"), invoked.toList())
            assertTrue(app.menu.performKeyEquivalent(key(second.nativeWindow, "{", shift = true)))
            await(native) { app.activeWindow == first }
            assertTrue(first.focus("input"))
            val field = first.nativeWindow.contentView!!.subviews.single() as NSTextField
            assertNotNull(field.currentEditor())
            value.value = "updated"
            first.renderer!!.renderUntilSettled()
            assertSame(renderer, first.renderer)
            assertEquals(field, first.nativeWindow.contentView!!.subviews.single())
            assertEquals("updated", field.stringValue)
            second.close()
            assertEquals(listOf(first), app.windows)
        } finally { app.dispose() }
    }

    @Test fun terminationWaitsForPreviouslyClosedAndVisibleWindowsExactlyOnce() {
        val native = native()
        var replies = 0
        val app = AppKitApplication("Test", nativeApplication = native, quitAfterLastWindow = false,
            replyToTermination = { assertTrue(it); replies++ })
        val completions = mutableMapOf<String, () -> Unit>()
        var releases = 0
        app.install()
        try {
            val first = app.openWindow(ApplicationWindow("one", "One"), callbacks = WindowCallbacks(release = {
                releases++; completions["one"] = it
            })) { text("first") }
            app.openWindow(ApplicationWindow("two", "Two"), callbacks = WindowCallbacks(release = {
                releases++; completions["two"] = it
            })) { text("second") }
            first.close()
            assertEquals(1, app.closingWindowCount)
            assertNull(first.renderer)
            assertEquals(NSTerminateLater, app.applicationShouldTerminate(native))
            assertTrue(app.windows.isEmpty())
            assertEquals(2, releases)
            completions.getValue("two")(); assertEquals(0, replies)
            completions.getValue("one")(); completions.getValue("one")()
            await(native) { replies == 1 }
            app.dispose(); assertEquals(2, releases)
        } finally { app.dispose() }
    }

    @Test fun closeVetoPreservesEveryWindowAndVisibilityTracksHideAndMinimize() {
        val native = native()
        val app = AppKitApplication("Test", nativeApplication = native, quitAfterLastWindow = false)
        var allowClose = false
        val visibility = mutableListOf<Boolean>()
        app.install()
        try {
            val window = app.openWindow(ApplicationWindow("one", "One"), callbacks = WindowCallbacks(
                canClose = { allowClose }, visibilityChanged = { visibility += it },
            )) { text("visible") }
            await(native) { window.isVisible }
            assertEquals(NSTerminateCancel, app.applicationShouldTerminate(native))
            assertNotNull(window.renderer)
            window.hide(); assertFalse(window.isVisible)
            window.show(); await(native) { window.isVisible }
            window.nativeWindow.miniaturize(null)
            await(native) { !window.isVisible }
            window.nativeWindow.deminiaturize(null)
            await(native) { window.isVisible }
            assertContains(visibility, false)
            allowClose = true
            assertEquals(NSTerminateNow, app.applicationShouldTerminate(native))
            assertFalse(window.isVisible)
        } finally { app.dispose() }
    }

    @Test fun disabledCommandsDoNotExecuteAndWindowCommandsHaveNoBackgroundFallback() {
        val native = native()
        val app = AppKitApplication("Test", nativeApplication = native, quitAfterLastWindow = false)
        var count = 0
        app.commands.register(ApplicationCommand("find.close", "Close Find", listOf(KeyShortcut("Escape", emptySet())),
            windowRequired = true, state = { CommandState(enabled = false) }) { count++ })
        app.install()
        try {
            val window = app.openWindow(ApplicationWindow("one", "One")) { text("one") }
            await(native) { app.activeWindow == window }
            assertFalse(app.execute("find.close"))
            window.hide()
            await(native) { app.activeWindow == null }
            assertFalse(app.execute("find.close"))
            assertEquals(0, count)
        } finally { app.dispose() }
    }

    private fun key(window: NSWindow, value: String, shift: Boolean = false): NSEvent = checkNotNull(
        NSEvent.keyEventWithType(NSEventTypeKeyDown, NSMakePoint(0.0, 0.0),
            NSEventModifierFlagCommand or if (shift) NSEventModifierFlagShift else 0uL, 0.0,
            window.windowNumber, null, value, value, false, 0u))

    private fun await(app: NSApplication, condition: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!condition() && start.elapsedNow().inWholeSeconds < 5) {
            app.nextEventMatchingMask(NSEventMaskAny, NSDate.dateWithTimeIntervalSinceNow(0.01), NSDefaultRunLoopMode, true)?.let(app::sendEvent)
            app.updateWindows()
        }
        assertTrue(condition(), "Native application state did not settle")
    }
}

@UiComponent
private fun ComponentScope.CloseWindowButton(close: () -> Unit) {
    button(onClick = close) { text("Close") }
}

@UiComponent
private fun ComponentScope.ApplicationInput(value: Cell<String>) {
    textInput(value.value, semantics = Semantics(testTag = "input"))
}
