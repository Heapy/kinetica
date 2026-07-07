package io.heapy.kinetica

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val nextRuntimeResourceCacheId = atomic(0L)

public class KineticaRuntime(
    public val debug: Boolean = true,
    journalCapacity: Int = 1_024,
    public val journalSampleInterval: Int? = if (debug) 1 else null,
    public val watchLoopRestartLimit: Int = 32,
    public val appResourceTtlMillis: Long? = null,
    public val exitTimeoutMillis: Long? = if (debug) 5_000 else null,
) {
    private val runtimeLock = SynchronizedObject()
    private val sequence = atomic(0L)
    private val warningSequence = atomic(0L)
    private var nextEventId = 0L
    private var nextFrameValueId = 0L
    private val events = mutableMapOf<String, (Any?) -> Unit>()
    private val frameValues = mutableMapOf<String, FrameValue>()
    private val entries = JournalBuffer<JournalEntry>(journalCapacity)
    private val warnings = JournalBuffer<RuntimeWarning>(journalCapacity)
    private val renderSnapshots = JournalBuffer<RenderSnapshot>(journalCapacity)
    private val pendingRenderCauses = linkedSetOf<String>()
    private val renderSubscriptions = mutableMapOf<ObservableCell<*>, Disposable>()
    private var hasRendered = false
    private var invalidated = false
    private val disposed = atomic(false)
    private var currentVirtualTimeMillis: Long = 0L
    private val runtimeResourceCacheId = nextRuntimeResourceCacheId.incrementAndGet()

    internal val appResourceCacheId: String = "app-$runtimeResourceCacheId"
    internal val requestResourceCacheId: String = "request-$runtimeResourceCacheId"

    public val effectScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeTasks = MutableStateFlow(0)

    public val virtualTimeMillis: Long
        get() = synchronized(runtimeLock) { currentVirtualTimeMillis }

    internal val isRecording: Boolean
        get() = journalSampleInterval != null

    init {
        require(watchLoopRestartLimit > 0) { "watchLoopRestartLimit must be positive." }
        require(journalSampleInterval == null || journalSampleInterval > 0) {
            "journalSampleInterval must be positive when provided."
        }
        require(appResourceTtlMillis == null || appResourceTtlMillis >= 0) {
            "appResourceTtlMillis must be non-negative."
        }
        require(exitTimeoutMillis == null || exitTimeoutMillis >= 0) {
            "exitTimeoutMillis must be non-negative."
        }
    }

    public fun render(content: @UiComponent ComponentScope.() -> Unit): RenderResult {
        val scope = ComponentScope(this)
        return render(scope, content)
    }

    public fun render(scope: ComponentScope, content: @UiComponent ComponentScope.() -> Unit): RenderResult {
        val cause = beginRender(scope)
        val renderDependencies = linkedSetOf<ObservableCell<*>>()
        ReadTracking.collect(
            observer = { cell ->
                if (cell is ObservableCell<*>) {
                    renderDependencies += cell
                }
            },
        ) {
            content(scope)
        }
        return commitRender(scope, cause, renderDependencies)
    }

    public suspend fun renderSuspend(content: @UiComponent (suspend ComponentScope.() -> Unit)): RenderResult {
        val scope = ComponentScope(this)
        return renderSuspend(scope, content)
    }

    public suspend fun renderSuspend(
        scope: ComponentScope,
        content: @UiComponent (suspend ComponentScope.() -> Unit),
    ): RenderResult {
        val cause = beginRender(scope)
        val renderDependencies = linkedSetOf<ObservableCell<*>>()
        ReadTracking.collectSuspend(
            observer = { cell ->
                if (cell is ObservableCell<*>) {
                    renderDependencies += cell
                }
            },
        ) {
            content(scope)
        }
        return commitRender(scope, cause, renderDependencies)
    }

    private fun beginRender(scope: ComponentScope): String {
        val cause = synchronized(runtimeLock) {
            val consumedCause = consumeRenderCauseLocked()
            invalidated = false
            consumedCause
        }
        record(JournalKind.RenderStarted, "render started", mapOf("cause" to cause))
        scope.beginRender()
        return cause
    }

    private fun commitRender(
        scope: ComponentScope,
        cause: String,
        renderDependencies: Set<ObservableCell<*>>,
    ): RenderResult {
        val node = scope.commitRender()
        updateRenderSubscriptions(renderDependencies)
        record(JournalKind.RenderCommitted, "render committed", mapOf("cause" to cause))
        recordRenderSnapshot(scope, node, cause)
        scope.runLayoutEffects()
        scope.runPostCommitEffects()
        val hasPendingInvalidation = synchronized(runtimeLock) {
            hasRendered = true
            invalidated
        }
        return RenderResult(node, journal(), hasPendingInvalidation, warnings())
    }

    private fun updateRenderSubscriptions(dependencies: Set<ObservableCell<*>>) {
        synchronized(runtimeLock) {
            val removed = renderSubscriptions.keys.filterNot { dependency -> dependency in dependencies }
            removed.forEach { dependency ->
                renderSubscriptions.remove(dependency)?.dispose()
            }
            dependencies.filterNot(renderSubscriptions::containsKey).forEach { dependency ->
                renderSubscriptions[dependency] = dependency.observe {
                    invalidate("cell write")
                }
            }
        }
    }

    internal fun registerEvent(callback: (Any?) -> Unit): String = synchronized(runtimeLock) {
        val id = "event-${nextEventId++}"
        events[id] = callback
        id
    }

    internal fun updateEvent(eventId: String, callback: (Any?) -> Unit): Boolean =
        synchronized(runtimeLock) {
            if (!events.containsKey(eventId)) {
                return@synchronized false
            }
            events[eventId] = callback
            true
        }

    internal fun removeEvent(eventId: String) {
        synchronized(runtimeLock) { events.remove(eventId) }
    }

    internal fun registeredEventCount(): Int = synchronized(runtimeLock) { events.size }

    internal fun createFrameValue(initial: Float): FrameValue = synchronized(runtimeLock) {
        val value = FrameValue("frame-${nextFrameValueId++}", initial)
        frameValues[value.id] = value
        value
    }

    public fun frameValue(id: String): FrameValue? =
        synchronized(runtimeLock) { frameValues[id] }

    public fun dispatch(eventId: String, payload: Any? = Unit) {
        val event = synchronized(runtimeLock) { events[eventId] } ?: return
        record(JournalKind.Event, "event dispatched", mapOf("eventId" to eventId))
        event(payload)
    }

    public fun invalidate(cause: String = "manual") {
        synchronized(runtimeLock) {
            invalidated = true
            pendingRenderCauses += cause
        }
    }

    public suspend fun awaitIdle() {
        withContext(Dispatchers.Default) {
            while (activeTasks.value > 0) {
                activeTasks.first { count -> count == 0 }
            }
        }
    }

    public val hasPendingInvalidation: Boolean
        get() = synchronized(runtimeLock) { invalidated }

    internal fun launchTrackedTask(
        onCompletion: (Throwable?) -> Unit = {},
        block: suspend CoroutineScope.(RuntimeTaskHandle) -> Unit,
    ): RuntimeTaskHandle {
        activeTasks.update { count -> count + 1 }
        val handle = RuntimeTaskHandle { markTaskIdle() }
        val job = effectScope.launch {
            block(handle)
        }
        handle.attach(job)
        job.invokeOnCompletion { cause ->
            handle.markIdle()
            onCompletion(cause)
        }
        return handle
    }

    public fun advanceVirtualTimeBy(millis: Long) {
        require(millis >= 0) { "millis must be non-negative." }
        if (millis == 0L) {
            return
        }
        val now = synchronized(runtimeLock) {
            currentVirtualTimeMillis += millis
            currentVirtualTimeMillis
        }
        record(
            JournalKind.VirtualTime,
            "virtual time advanced",
            mapOf("millis" to millis.toString(), "now" to now.toString()),
        )
        invalidate("virtual time")
    }

    internal fun record(
        kind: JournalKind,
        message: String,
        attributes: Map<String, String> = emptyMap(),
    ): JournalEntry? {
        val sampleInterval = journalSampleInterval ?: return null
        val entry = JournalEntry(sequence.incrementAndGet(), kind, message, attributes)
        return if (entry.sequence % sampleInterval.toLong() == 0L) {
            entry.also(entries::append)
        } else {
            null
        }
    }

    internal fun warn(
        code: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
    ): RuntimeWarning =
        RuntimeWarning(warningSequence.incrementAndGet(), code, message, attributes).also(warnings::append)

    public fun journal(): List<JournalEntry> = entries.entries()

    public fun warnings(): List<RuntimeWarning> = warnings.entries()

    public fun exportJournal(): ExecutionJournal =
        ExecutionJournal(
            entries = journal(),
            renderSnapshots = renderSnapshots.entries(),
        )

    public fun replay(): JournalReplay =
        JournalReplay(exportJournal())

    private fun consumeRenderCauseLocked(): String {
        val cause = when {
            pendingRenderCauses.isNotEmpty() -> pendingRenderCauses.joinToString(separator = ",")
            hasRendered -> "manual"
            else -> "initial"
        }
        pendingRenderCauses.clear()
        return cause
    }

    private fun recordRenderSnapshot(scope: ComponentScope, node: Node, cause: String) {
        if (journalSampleInterval == null) {
            return
        }
        val draft = scope.slotSnapshot(sequence = 0L)
        val snapshotEntry = record(
            JournalKind.SlotSnapshot,
            "slot snapshot",
            mapOf("slots" to draft.slots.size.toString()),
        ) ?: return
        val slots = draft.copy(sequence = snapshotEntry.sequence)
        renderSnapshots.append(
            RenderSnapshot(
                sequence = snapshotEntry.sequence,
                cause = cause,
                tree = node,
                slots = slots,
            ),
        )
    }

    private fun markTaskIdle() {
        activeTasks.update { count -> (count - 1).coerceAtLeast(0) }
    }

    /**
     * Tears down the runtime: releases every render subscription (so long-lived cells lose
     * the observers this runtime established) and cancels [effectScope]. Idempotent.
     */
    public fun dispose() {
        if (!disposed.compareAndSet(expect = false, update = true)) {
            return
        }
        synchronized(runtimeLock) {
            renderSubscriptions.values.forEach(Disposable::dispose)
            renderSubscriptions.clear()
        }
        effectScope.cancel()
    }
}

public data class RenderResult(
    val tree: Node,
    val journal: List<JournalEntry>,
    val invalidated: Boolean,
    val warnings: List<RuntimeWarning> = emptyList(),
)

internal class RuntimeTaskHandle(
    private val markIdleCallback: () -> Unit,
) {
    private val active = atomic(true)
    private var job: Job? = null

    fun attach(job: Job) {
        this.job = job
    }

    fun markIdle() {
        if (active.compareAndSet(expect = true, update = false)) {
            markIdleCallback()
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
