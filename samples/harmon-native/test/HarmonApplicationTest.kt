@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.samples.harmon

import io.heapy.kinetica.appkit.AppKitApplication
import platform.AppKit.*
import platform.Foundation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlin.test.*
import kotlin.time.TimeSource

class HarmonApplicationTest {
    @Test fun monitorWindowsShareFrameworkCommandsButKeepIndependentSessions() {
        val native = NSApplication.sharedApplication()
        native.finishLaunching(); native.activateIgnoringOtherApps(true)
        val backend = AppKitApplication("Harmon Test", nativeApplication = native, quitAfterLastWindow = false)
        val app = HarmonApplication({ MonitorSession().also { it.setFrozen(true) } }, backend)
        try {
            app.start()
            val first = backend.windows.single()
            await(native) { backend.activeWindow == first }
            assertTrue(backend.execute("window.tab"))
            val second = backend.windows.last()
            await(native) { backend.activeWindow == second }
            assertEquals(2, second.nativeWindow.tabGroup!!.windows.size)
            assertTrue(backend.execute("monitor.freeze"))
            assertFalse(app.sessions.getValue(second.id).state.value.frozen)
            assertTrue(app.sessions.getValue(first.id).state.value.frozen)
            assertFalse(backend.execute("monitor.refresh"))
            backend.selectTab(0)
            await(native) { backend.activeWindow == first }
            val oldSequence = app.sessions.getValue(first.id).state.value.snapshot.sequence
            assertTrue(backend.execute("monitor.refresh"))
            assertTrue(app.sessions.getValue(first.id).state.value.snapshot.sequence > oldSequence)
            assertTrue(backend.execute("monitor.find"))
            val focused = (first.nativeWindow.firstResponder as NSTextView).delegate as NSTextField
            assertEquals("search", focused.identifier)
            first.close()
            assertFalse(first.id in app.sessions)
            assertNull(first.renderer)
            assertEquals(listOf(second), backend.windows)
        } finally { app.dispose() }
    }

    @Test fun hidingAFrameworkWindowCancelsPollingAndClosingReleasesItsTransportOnce() {
        val native = NSApplication.sharedApplication()
        native.finishLaunching(); native.activateIgnoringOtherApps(true)
        var requests = 0
        var cancelled = 0
        var closed = 0
        val source = object : LiveSource {
            override suspend fun fetch(): LivePayload {
                requests++
                try { awaitCancellation() } catch (cancel: CancellationException) { cancelled++; throw cancel }
            }
            override fun close() { closed++ }
        }
        val backend = AppKitApplication("Harmon Test", nativeApplication = native, quitAfterLastWindow = false)
        val app = HarmonApplication({ MonitorSession(source) }, backend)
        try {
            app.start()
            val window = backend.windows.single()
            await(native) { requests == 1 }
            window.hide()
            await(native) { cancelled == 1 && app.sessions.getValue(window.id).connection.value.status == ConnectionStatus.Paused }
            assertEquals(1, requests, "A hidden window must not begin another request")
            window.show()
            await(native) { requests == 2 }
            window.close()
            await(native) { cancelled == 2 && closed == 1 && backend.closingWindowCount == 0 }
            assertTrue(app.sessions.isEmpty())
            app.dispose()
            assertEquals(1, closed)
        } finally { app.dispose() }
    }

    private fun await(app: NSApplication, predicate: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!predicate() && start.elapsedNow().inWholeSeconds < 5) {
            app.nextEventMatchingMask(NSEventMaskAny, NSDate.dateWithTimeIntervalSinceNow(0.01), NSDefaultRunLoopMode, true)?.let(app::sendEvent)
            app.updateWindows()
        }
        assertTrue(predicate(), "Harmon application did not reach the expected state")
    }
}
