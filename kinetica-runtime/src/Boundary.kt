package io.heapy.kinetica

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

public data class ErrorInfo(
    val boundaryId: String,
)

public class BoundaryRetry internal constructor(
    private val retryBlock: () -> Unit,
) {
    public fun retry() {
        retryBlock()
    }
}

public fun ComponentScope.errorBoundary(
    fallback: ComponentScope.(Throwable, ErrorInfo, BoundaryRetry) -> Unit,
    content: ComponentScope.() -> Unit,
) {
    // Boundary state (captured error, retry) lives outside the tracked cell graph, so a
    // memoized each row containing a boundary could replay a stale fallback after retry().
    markEachCapturesUnsafe()
    val slotKey = nextSlotKey(null)
    registerSlot(SlotMetadata(slotKey, slotId = null, persistent = false, transient = false))
    val state = slot(slotKey) { ErrorBoundaryState(boundaryId = "boundary:$slotKey") }
    val retry = BoundaryRetry {
        state.clear()
        runtime.invalidate("boundary retry")
    }
    val captured = state.error
    if (captured != null) {
        fallback(captured, ErrorInfo(boundaryId = state.boundaryId), retry)
        return
    }

    try {
        val node = withErrorBoundary(state) {
            collect(content).toNode()
        }
        emit(node)
    } catch (pending: ResourcePendingException) {
        throw pending
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        state.capture(runtime, error)
        fallback(error, ErrorInfo(boundaryId = state.boundaryId), retry)
    }
}

internal class ErrorBoundaryState(
    val boundaryId: String,
) {
    var error: Throwable? = null
        private set

    fun capture(runtime: KineticaRuntime, error: Throwable) {
        this.error = error
        runtime.record(
            JournalKind.BoundaryError,
            "errorBoundary caught error",
            mapOf("boundaryId" to boundaryId, "error" to error.toString()),
        )
        runtime.invalidate("boundary error")
    }

    fun clear() {
        error = null
    }
}

public fun ComponentScope.loadingBoundary(
    retainPrevious: Boolean = true,
    fallback: ComponentScope.() -> Unit,
    content: ComponentScope.() -> Unit,
) {
    val slotKey = nextSlotKey(null)
    registerSlot(SlotMetadata(slotKey, slotId = null, persistent = false, transient = false))
    val state = slot(slotKey) { LoadingBoundaryState() }
    runtime.record(
        JournalKind.RenderStarted,
        "loadingBoundary",
        mapOf("retainPrevious" to retainPrevious.toString()),
    )
    try {
        val node = collect(content).toNode()
        state.previous = node
        emit(node)
    } catch (pending: ResourcePendingException) {
        runtime.record(
            JournalKind.ResourceLoad,
            "loading boundary pending",
            mapOf(
                "key" to pending.key,
                "retainPrevious" to retainPrevious.toString(),
                "retained" to (state.previous != null).toString(),
            ),
        )
        if (retainPrevious && state.previous != null) {
            emit(state.previous ?: FragmentNode())
        } else {
            fallback()
        }
    }
}

private class LoadingBoundaryState {
    var previous: Node? = null
}

public fun ComponentScope.suspendSubtree(
    key: String? = null,
    fallback: ComponentScope.() -> Unit,
    content: suspend ComponentScope.() -> Unit,
) {
    val slotKey = nextSlotKey(key)
    registerSlot(SlotMetadata(slotKey, slotId = null, persistent = false, transient = true))
    val state = slot(slotKey) {
        SuspendSubtreeState(
            key = slotKey,
            scope = ComponentScope(runtime, instanceId = "$instanceId/suspend:$slotKey"),
        )
    }

    val error = state.error
    if (error != null) {
        throw error
    }

    val node = state.node
    if (node != null) {
        emit(node)
        return
    }

    if (!state.running) {
        state.start(runtime, content)
    }
    val fallbackNode = collect(fallback).toNode()
    state.previousFallback = fallbackNode
    emit(fallbackNode)
}

private class SuspendSubtreeState(
    private val key: String,
    private val scope: ComponentScope,
) : Disposable {
    var node: Node? = null
    var previousFallback: Node? = null
    var error: Throwable? = null
    var running: Boolean = false
        private set
    private var task: RuntimeTaskHandle? = null

    fun start(
        runtime: KineticaRuntime,
        content: suspend ComponentScope.() -> Unit,
    ) {
        running = true
        runtime.record(JournalKind.DeferredSubtree, "suspend subtree pending", mapOf("key" to key))
        task = runtime.launchTrackedTask {
            try {
                node = runtime.renderSuspend(scope, content).tree
                runtime.record(JournalKind.DeferredSubtree, "suspend subtree ready", mapOf("key" to key))
            } catch (failure: Throwable) {
                if (failure is CancellationException) {
                    throw failure
                }
                error = failure
                runtime.record(
                    JournalKind.DeferredSubtree,
                    "suspend subtree failed",
                    mapOf("key" to key, "error" to failure.toString()),
                )
            } finally {
                running = false
                runtime.invalidate("suspend subtree")
            }
        }
    }

    override fun dispose() {
        task?.cancel()
        task = null
        scope.dispose()
    }
}

public interface ExitScope {
    public val key: String
    public fun complete()
}

public fun ComponentScope.onExit(block: suspend ExitScope.() -> Unit) {
    val group = currentExitGroup()
    if (group != null) {
        group.callbacks += block
        runtime.record(JournalKind.Leaving, "onExit registered", mapOf("key" to group.key))
    } else {
        runtime.record(JournalKind.Leaving, "onExit ignored outside exitGroup", mapOf("block" to block.toString()))
    }
}

public fun ComponentScope.exitGroup(
    key: Any,
    visible: Boolean,
    content: ComponentScope.() -> Unit,
) {
    val state = exitGroupState(key.toString())
    if (visible) {
        state.cancelTasks()
        state.generation += 1
        state.phase = ExitPhase.Active
        state.pendingCallbacks = 0
        state.callbacks.clear()
        val nodes = collectExitGroup(state, content)
        state.retained = nodes.toNode()
        emit(state.retained ?: FragmentNode())
        return
    }

    val retained = state.retained ?: return
    if (state.phase == ExitPhase.Active) {
        state.phase = ExitPhase.Leaving
        state.generation += 1
        state.pendingCallbacks = state.callbacks.size
        runtime.record(JournalKind.Leaving, "exit started", mapOf("key" to state.key))

        if (state.pendingCallbacks == 0) {
            completeExit(state.key)
            return
        }

        val generation = state.generation
        state.callbacks.forEach { callback ->
            state.addTask(
                runtime.launchTrackedTask {
                    callback(ExitScopeImpl(state.key) { completeExitCallback(state.key, generation) })
                },
            )
        }
        val timeoutMillis = runtime.exitTimeoutMillis
        if (timeoutMillis != null) {
            state.addTask(
                runtime.launchTrackedTask {
                    delay(timeoutMillis)
                    if (state.phase == ExitPhase.Leaving && state.generation == generation) {
                        runtime.record(
                            JournalKind.Leaving,
                            "exit timeout",
                            mapOf("key" to state.key, "timeoutMillis" to timeoutMillis.toString()),
                        )
                        completeExit(state.key)
                    }
                },
            )
        }
    }

    if (state.phase == ExitPhase.Leaving) {
        emit(retained.asLeaving())
    }
}

internal enum class ExitPhase {
    Active,
    Leaving,
    Disposed,
}

internal class ExitGroupState(
    val key: String,
) {
    val stateLock = SynchronizedObject()
    var phase: ExitPhase = ExitPhase.Active
    var retained: Node? = null
    var generation: Int = 0
    var pendingCallbacks: Int = 0
    val callbacks: MutableList<suspend ExitScope.() -> Unit> = mutableListOf()
    private val tasks: MutableList<RuntimeTaskHandle> = mutableListOf()
    private val taskLock = SynchronizedObject()

    fun addTask(task: RuntimeTaskHandle) {
        synchronized(taskLock) {
            tasks += task
        }
    }

    fun cancelTasks() {
        val snapshot = synchronized(taskLock) {
            tasks.toList().also { tasks.clear() }
        }
        snapshot.forEach { task -> task.cancel() }
    }
}

private class ExitScopeImpl(
    override val key: String,
    private val completeCallback: () -> Unit,
) : ExitScope {
    private var completed = false

    override fun complete() {
        if (!completed) {
            completed = true
            completeCallback()
        }
    }
}

private fun List<Node>.toNode(): Node = when (size) {
    0 -> FragmentNode()
    1 -> single()
    else -> FragmentNode(this)
}

public fun Node.asLeaving(): Node = when (this) {
    is FragmentNode -> copy(
        children = children.map { it.asLeaving() },
        semantics = semantics?.copy(leaving = true),
    )
    is HostNode -> copy(
        props = props.filterKeys { !it.startsWith("event:") },
        children = children.map { it.asLeaving() },
        semantics = semantics.copyLeaving(),
    )
    is TextNode -> copy(semantics = semantics.copyLeaving())
    is ClientRef -> copy(semantics = semantics.copyLeaving())
}

private fun Semantics?.copyLeaving(): Semantics =
    (this ?: Semantics()).copy(leaving = true)

public class FrameValue internal constructor(
    public val id: String,
    initial: Float,
) {
    private val listeners = mutableSetOf<(Float) -> Unit>()

    public var value: Float = initial
        private set

    public fun snapTo(value: Float) {
        if (this.value.frameValueEquals(value)) {
            return
        }
        this.value = value
        listeners.toList().forEach { listener -> listener(value) }
    }

    public fun observe(listener: (Float) -> Unit): Disposable {
        listeners += listener
        return Disposable { listeners -= listener }
    }

    public fun commitTo(cell: MutableCell<Float>) {
        cell.value = value
    }

    public fun commitTo(update: (Float) -> Unit) {
        update(value)
    }
}

public fun ComponentScope.frameValue(initial: Float): FrameValue =
    nextSlotKey(null).let { slotKey ->
        registerSlot(SlotMetadata(slotKey, slotId = null, persistent = false, transient = false))
        slot(slotKey) { runtime.createFrameValue(initial) }
    }

private fun Float.frameValueEquals(other: Float): Boolean =
    this == other || (isNaN() && other.isNaN())
