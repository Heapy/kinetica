@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.samples.harmon

import io.heapy.kinetica.*
import io.heapy.kinetica.appkit.OutlineTableEvent
import io.heapy.kinetica.appkit.outlineTable
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

class MonitorSession(private val source: LiveSource? = null) {
    val isLive = source != null
    val state = store(if (isLive) MonitorState(snapshot = ProcessSnapshot(0, emptyList(), SampleStatus.Warming)) else MonitorState())
    val connection = store(ConnectionInfo(ConnectionStatus.Connecting, "Connecting to Harmon…"))
    private var windowVisible = true
    private var applicationVisible = true
    private var sourceClosed = false
    private var running: Job? = null
    private var stopping = false
    private val ownedSource = source?.let { delegate -> object : LiveSource {
        override suspend fun fetch(): LivePayload = delegate.fetch()
        override fun close() { if (!sourceClosed) { sourceClosed = true; delegate.close() } }
    } }
    private val live = ownedSource?.let { LiveConnection(it,
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
        if (stopping) return@withContext
        check(running == null) { "A monitor session may have only one polling effect" }
        running = currentCoroutineContext()[Job]
        try {
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
        } finally {
            running = null
            ownedSource?.close()
        }
    }

    /** Completes after the polling coroutine and its Foundation transport have stopped. */
    fun close(completed: () -> Unit) {
        stopping = true
        setWindowVisible(false)
        val task = running
        if (task == null) { ownedSource?.close(); completed(); return }
        task.invokeOnCompletion { dispatch_async(dispatch_get_main_queue()) { completed() } }
        task.cancel()
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

fun main(arguments: Array<String>) {
    val sample = "--sample" in arguments
    val frozen = "--frozen" in arguments
    val endpoint = arguments.firstOrNull { it.startsWith("--endpoint-file=") }?.substringAfter('=')
    HarmonApplication(newSession = {
        MonitorSession(if (sample) null else LocalLiveSource(endpoint ?: defaultEndpointPath())).also {
            if (frozen) it.setFrozen(true)
        }
    }).run()
}
