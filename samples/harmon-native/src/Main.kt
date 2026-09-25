@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.samples.harmon

import io.heapy.kinetica.*
import io.heapy.kinetica.appkit.AppKitKineticaApp
import io.heapy.kinetica.appkit.OutlineTableEvent
import io.heapy.kinetica.appkit.outlineTable
import io.heapy.kinetica.appkit.renderAppKitApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.AppKit.*
import platform.Foundation.NSMakeRect
import platform.Foundation.NSMakeSize
import platform.Foundation.NSNotification
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObject

class MonitorSession(private val source: LiveSource? = null) {
    val isLive = source != null
    val state = store(if (isLive) MonitorState(snapshot = ProcessSnapshot(0, emptyList(), SampleStatus.Warming)) else MonitorState())
    val connection = store(ConnectionInfo(ConnectionStatus.Connecting, "Connecting to Harmon…"))
    private var windowVisible = true
    private var applicationVisible = true
    private val live = source?.let { LiveConnection(it,
        onSnapshot = { state.value = state.value.withSnapshot(it) }, onConnection = { connection.value = it }) }

    fun setFrozen(frozen: Boolean) {
        state.value = state.value.copy(frozen = frozen)
        updateActivity()
    }
    fun setWindowVisible(visible: Boolean) { windowVisible = visible; updateActivity() }
    fun setApplicationVisible(visible: Boolean) { applicationVisible = visible; updateActivity() }
    fun retry() { live?.retry() }
    private fun updateActivity() { live?.setActive(windowVisible && applicationVisible && !state.value.frozen) }

    suspend fun run() = withContext(Dispatchers.Main) {
        if (live != null) {
            updateActivity()
            live.run()
        } else {
            while (true) {
                delay(1_000)
                val latest = state.value
                if (windowVisible && applicationVisible && !latest.frozen && latest.snapshot.status == SampleStatus.Ready) {
                    state.value = latest.nextSample()
                }
            }
        }
    }
}

@UiComponent
fun ComponentScope.HarmonMonitor(session: MonitorSession) {
    val current = session.state.value
    val projection = projectMonitor(current)
    val connection = session.connection.value
    launchEffect { session.run() }
    host("column", props = mapOf("padding" to "20", "spacing" to "12")) {
        host("row", props = mapOf("spacing" to "12")) {
            host("appkit:label", props = mapOf("value" to "Harmon", "fontSize" to "25"))
            host("appkit:label", props = mapOf("value" to if (session.isLive) "Native monitor · local Harmon agent" else "Native preview · deterministic sample data", "secondary" to "true"))
            host("appkit:spacer")
            button(onClick = event { session.setFrozen(!session.state.value.frozen) },
                semantics = Semantics(label = if (current.frozen) "Resume live updates" else "Freeze snapshot", testTag = "freeze")) {
                text(if (current.frozen) "Resume Live" else "Freeze Snapshot")
            }
            if (session.isLive) {
                button(onClick = event { session.retry() }, enabled = !current.frozen) { text("Reconnect") }
            } else {
                button(onClick = event { session.state.value = session.state.value.nextSample() },
                semantics = Semantics(label = "Advance one sample", testTag = "advance"), enabled = current.frozen) {
                    text("Next Sample")
                }
            }
        }
        row {
            host("appkit:label", props = mapOf("value" to "${projection.processCount} processes", "fontSize" to "16"))
            host("appkit:label", props = mapOf("value" to "${current.preset.metrics.joinToString(" & ") { it.label }} · Self and descendant totals", "secondary" to "true"))
            host("appkit:spacer")
            host("appkit:label", props = mapOf("value" to "${if (current.frozen) "Frozen" else if (session.isLive) connection.status.name else "Live"} · sample ${current.snapshot.sourceSequence ?: current.snapshot.sequence}", "secondary" to "true"),
                semantics = Semantics(testTag = "sample-status"))
        }
        host("row", props = mapOf("spacing" to "8")) {
            textInput(current.query, onInput = event<String> { session.state.value = session.state.value.withQuery(it) },
                placeholder = "Search process name or PID", semantics = Semantics(label = "Search processes", testTag = "search", focusable = true))
            button(onClick = event { session.state.value = session.state.value.withQuery("") }, enabled = current.query.isNotEmpty()) { text("Clear") }
            each(MetricPreset.entries, key = { it.name }) { preset ->
                button(onClick = event { session.state.value = session.state.value.withPreset(preset) }, enabled = preset != session.state.value.preset) {
                    text(preset.label)
                }
            }
        }
        host("appkit:label", props = mapOf("value" to if (session.isLive) connection.message else when (current.snapshot.status) {
            SampleStatus.Ready -> "⚠  High CPU activity in Xcode and its helper processes · ≥ marks partial totals; — means unavailable"
            SampleStatus.Warming -> "Warming up · waiting for the next measurement; the last snapshot remains visible"
            SampleStatus.Stale -> "Updates unavailable · showing the last successful snapshot"
        }), semantics = Semantics(testTag = "notice"))
        if (session.isLive) {
            host("appkit:label", props = mapOf("value" to (current.snapshot.message
                ?: "${current.snapshot.inaccessibleProcessCount} inaccessible processes · ≥ partial totals · — unavailable").take(160), "secondary" to "true"))
        }
        outlineTable(projection.table, semantics = Semantics(label = "Processes", testTag = "process-tree")) { action ->
            val latest = session.state.value
            session.state.value = when (action) {
                is OutlineTableEvent.SelectionChanged -> latest.copy(selected = action.key)
                is OutlineTableEvent.ExpansionChanged -> latest.withExpansion(action.key, action.expanded)
                is OutlineTableEvent.SortChanged -> latest.copy(sort = action.sort)
            }
        }
        row {
            val selected = current.snapshot.allProcesses().firstOrNull { it.key == current.selected }
            host("appkit:label", props = mapOf("value" to if (selected == null) "${projection.matchingCount} matching processes · Select a row to inspect its identity"
                else "${selected.name} · PID ${selected.pid} · ${selected.key}", "secondary" to "true"), semantics = Semantics(testTag = "selection"))
            host("appkit:spacer")
            button(onClick = event { session.state.value = session.state.value.withAllExpanded(false) }) { text("Collapse All") }
            button(onClick = event { session.state.value = session.state.value.withAllExpanded(true) }) { text("Expand All") }
        }
        if (session.isLive) {
            row {
                host("appkit:label", props = mapOf("value" to "Last capture: ${current.snapshot.capturedAt ?: "waiting for data"}", "secondary" to "true"))
                host("appkit:spacer")
                host("appkit:label", props = mapOf("value" to "Local Harmon agent", "secondary" to "true"))
            }
        } else row {
            host("appkit:label", props = mapOf("value" to "Preview connection state:", "secondary" to "true"))
            each(SampleStatus.entries, key = { it.name }) { status ->
                button(onClick = event { session.state.value = session.state.value.copy(snapshot = session.state.value.snapshot.copy(status = status)) },
                    enabled = session.state.value.snapshot.status != status) { text(status.name) }
            }
            host("appkit:spacer")
            host("appkit:label", props = mapOf("value" to "Local fixtures only · no collector connection", "secondary" to "true"))
        }
    }
}

private class MonitorWindowDelegate(
    private val session: MonitorSession,
    private val renderer: AppKitKineticaApp,
) : NSObject(), NSWindowDelegateProtocol, NSApplicationDelegateProtocol {
    override fun windowWillClose(notification: NSNotification) {
        dispose()
        NSApplication.sharedApplication().terminate(null)
    }
    override fun windowDidMiniaturize(notification: NSNotification) { session.setWindowVisible(false) }
    override fun windowDidDeminiaturize(notification: NSNotification) { session.setWindowVisible(true) }
    override fun applicationDidHide(notification: NSNotification) { session.setApplicationVisible(false) }
    override fun applicationDidUnhide(notification: NSNotification) { session.setApplicationVisible(true) }
    override fun applicationWillTerminate(notification: NSNotification) { dispose() }
    fun dispose() {
        session.setWindowVisible(false)
        renderer.dispose()
    }
}

fun main(arguments: Array<String>) {
    val application = NSApplication.sharedApplication()
    application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
    installMenu(application)
    val window = NSWindow(
        contentRect = NSMakeRect(0.0, 0.0, 1120.0, 720.0),
        styleMask = NSWindowStyleMaskTitled or NSWindowStyleMaskClosable or NSWindowStyleMaskMiniaturizable or NSWindowStyleMaskResizable,
        backing = NSBackingStoreBuffered,
        defer = false,
    )
    window.title = "Harmon — Native Preview"
    window.setMinSize(NSMakeSize(900.0, 500.0))
    window.setReleasedWhenClosed(false)
    val sample = "--sample" in arguments
    val endpoint = arguments.firstOrNull { it.startsWith("--endpoint-file=") }?.substringAfter('=')
    val session = MonitorSession(if (sample) null else LocalLiveSource(endpoint ?: defaultEndpointPath()))
    if ("--frozen" in arguments) session.setFrozen(true)
    val renderer = renderAppKitApp(window.contentView!!, runtime = KineticaRuntime(debug = false)) { HarmonMonitor(session) }
    val delegate = MonitorWindowDelegate(session, renderer)
    window.delegate = delegate
    application.delegate = delegate
    window.center()
    window.makeKeyAndOrderFront(null)
    application.activateIgnoringOtherApps(true)
    try {
        application.run()
    } finally {
        // This use after run() keeps AppKit's weak delegate alive until shutdown.
        delegate.dispose()
        window.delegate = null
        application.delegate = null
    }
}

private fun installMenu(application: NSApplication) {
    val menu = NSMenu()
    val appMenu = NSMenuItem()
    appMenu.submenu = NSMenu().apply {
        addItemWithTitle("Hide Harmon Preview", NSSelectorFromString("hide:"), "h")
        addItemWithTitle("Quit Harmon Preview", NSSelectorFromString("terminate:"), "q")
    }
    menu.addItem(appMenu)
    val edit = NSMenuItem()
    edit.submenu = NSMenu("Edit").apply {
        addItemWithTitle("Undo", NSSelectorFromString("undo:"), "z")
        addItemWithTitle("Cut", NSSelectorFromString("cut:"), "x")
        addItemWithTitle("Copy", NSSelectorFromString("copy:"), "c")
        addItemWithTitle("Paste", NSSelectorFromString("paste:"), "v")
        addItemWithTitle("Select All", NSSelectorFromString("selectAll:"), "a")
    }
    menu.addItem(edit)
    application.mainMenu = menu
}
