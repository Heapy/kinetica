package io.heapy.kinetica

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.CoroutineContext

public interface EffectHandle {
    public fun cancel()
}

public interface EffectScope : CoroutineScope {
    public suspend fun awaitDispose(cleanup: suspend () -> Unit = {})
}

public class LayoutScope internal constructor()

public class EventScope internal constructor(
    public val runtime: KineticaRuntime,
)

internal class EffectScopeImpl(
    override val coroutineContext: CoroutineContext,
    private val markIdle: () -> Unit = {},
) : EffectScope {
    override suspend fun awaitDispose(cleanup: suspend () -> Unit) {
        markIdle()
        try {
            suspendCancellableCoroutine<Unit> { }
        } finally {
            cleanup()
        }
    }
}

internal interface ManagedEffectState : EffectHandle

private class LaunchEffectState(
    private val runtime: KineticaRuntime,
) : ManagedEffectState {
    private var started = false
    private var cancelled = false
    private var block: suspend EffectScope.() -> Unit = {}
    private var errorBoundary: ErrorBoundaryState? = null
    private var task: RuntimeTaskHandle? = null

    fun configure(
        block: suspend EffectScope.() -> Unit,
        errorBoundary: ErrorBoundaryState?,
    ) {
        this.errorBoundary = errorBoundary
        if (!started) {
            this.block = block
        }
    }

    fun runAfterCommit() {
        if (started || cancelled) {
            return
        }
        started = true
        runtime.record(JournalKind.EffectStarted, "launchEffect started")
        val activeBlock = block
        task = runtime.launchTrackedTask(
            onCompletion = {
                runtime.record(JournalKind.EffectCancelled, "launchEffect completed")
            },
        ) { handle ->
            try {
                EffectScopeImpl(coroutineContext, handle::markIdle).activeBlock()
            } catch (error: Throwable) {
                runtime.reportEffectError(key = "launchEffect", errorBoundary = errorBoundary, error = error)
            }
        }
    }

    override fun cancel() {
        cancelled = true
        task?.cancel()
        task = null
    }
}

private class WatchEffectState<T>(
    private val runtime: KineticaRuntime,
    private val key: String,
) : ManagedEffectState {
    private var initialized = false
    private var cancelled = false
    private var loopStopped = false
    private var restartCount = 0
    private var last: T? = null
    private var source: () -> T = { error("watch source was not configured") }
    private var equals: EqualityPolicy<T> = EqualityPolicy.structural()
    private var block: suspend EffectScope.(T) -> Unit = {}
    private var errorBoundary: ErrorBoundaryState? = null
    private var task: RuntimeTaskHandle? = null
    private val sourceSubscriptions = mutableMapOf<ObservableCell<*>, Disposable>()
    private val trace = ArrayDeque<String>()

    fun configure(
        source: () -> T,
        equals: EqualityPolicy<T>,
        errorBoundary: ErrorBoundaryState?,
        block: suspend EffectScope.(T) -> Unit,
    ) {
        this.source = source
        this.equals = equals
        this.errorBoundary = errorBoundary
        this.block = block
    }

    fun runAfterCommit() {
        if (cancelled || loopStopped) {
            return
        }
        val next = readSource()
        if (!initialized) {
            initialized = true
            last = next
            runtime.record(JournalKind.WatchRestart, "watch started", mapOf("value" to next.toString()))
            startIfAllowed(next)
            return
        }

        @Suppress("UNCHECKED_CAST")
        val previous = last as T
        if (!equals.equivalent(previous, next)) {
            task?.cancel()
            last = next
            runtime.record(JournalKind.WatchRestart, "watch restarted", mapOf("value" to next.toString()))
            startIfAllowed(next)
        } else {
            restartCount = 0
            trace.clear()
        }
    }

    private fun readSource(): T {
        val dependencies = linkedSetOf<ObservableCell<*>>()
        val next = ReadTracking.collect(
            observer = { cell ->
                if (cell is ObservableCell<*>) {
                    dependencies += cell
                }
            },
            block = source,
        )
        updateSourceSubscriptions(dependencies)
        return next
    }

    private fun updateSourceSubscriptions(dependencies: Set<ObservableCell<*>>) {
        val removed = sourceSubscriptions.keys.filterNot { dependency -> dependency in dependencies }
        removed.forEach { dependency ->
            sourceSubscriptions.remove(dependency)?.dispose()
        }
        dependencies.filterNot(sourceSubscriptions::containsKey).forEach { dependency ->
            sourceSubscriptions[dependency] = dependency.observe {
                runtime.invalidate("watch source")
            }
        }
    }

    private fun startIfAllowed(value: T) {
        if (runtime.debug && !recordRestart(value)) {
            return
        }
        val activeBlock = block
        task = runtime.launchTrackedTask { handle ->
            try {
                EffectScopeImpl(coroutineContext, handle::markIdle).activeBlock(value)
            } catch (error: Throwable) {
                runtime.reportEffectError(key = key, errorBoundary = errorBoundary, error = error)
            }
        }
    }

    private fun recordRestart(value: T): Boolean {
        restartCount += 1
        trace += value.toString()
        while (trace.size > runtime.watchLoopRestartLimit) {
            trace.removeFirst()
        }
        if (restartCount <= runtime.watchLoopRestartLimit) {
            return true
        }

        loopStopped = true
        task?.cancel()
        task = null
        runtime.record(
            JournalKind.WatchLoop,
            "watch loop stopped",
            mapOf(
                "effectKey" to key,
                "restarts" to restartCount.toString(),
                "limit" to runtime.watchLoopRestartLimit.toString(),
                "trace" to trace.joinToString(separator = " -> "),
            ),
        )
        return false
    }

    override fun cancel() {
        cancelled = true
        task?.cancel()
        task = null
        sourceSubscriptions.values.forEach { subscription -> subscription.dispose() }
        sourceSubscriptions.clear()
    }
}

private class StableEvent<A>(
    private val runtime: KineticaRuntime,
    var block: EventScope.(A) -> Unit,
) {
    val callback: (A) -> Unit = { argument ->
        EventScope(runtime).block(argument)
    }
}

private class StableUnitEvent(
    private val runtime: KineticaRuntime,
    var block: EventScope.() -> Unit,
) {
    val callback: () -> Unit = {
        EventScope(runtime).block()
    }
}

public fun ComponentScope.launchEffect(block: suspend EffectScope.() -> Unit): EffectHandle {
    val key = nextEffectKey(SlotKind.Launch)
    registerSlot(SlotMetadata(key, slotId = null, persistent = false, transient = true))
    val state = checkedSlot(key, LaunchEffectState::class) { LaunchEffectState(runtime) }
    state.configure(block, currentErrorBoundary())
    schedulePostCommitEffect { state.runAfterCommit() }
    return state
}

public fun <T> ComponentScope.watch(
    source: () -> T,
    equals: EqualityPolicy<T> = EqualityPolicy.structural(),
    block: suspend EffectScope.(T) -> Unit,
): EffectHandle {
    val key = nextEffectKey(SlotKind.Watch)
    registerSlot(SlotMetadata(key, slotId = null, persistent = false, transient = true))
    val state = checkedSlot(key, WatchEffectState::class) { WatchEffectState<T>(runtime, key) }
    state.configure(source, equals, currentErrorBoundary(), block)
    schedulePostCommitEffect { state.runAfterCommit() }
    return state
}

public fun ComponentScope.layoutEffect(block: LayoutScope.() -> Unit) {
    scheduleLayoutEffect {
        LayoutScope().block()
    }
}

public fun <A> ComponentScope.event(block: EventScope.(A) -> Unit): (A) -> Unit {
    val key = nextEventKey(SlotKind.TypedEvent)
    val stable = checkedSlot(key, StableEvent::class) { StableEvent(runtime, block) }
    stable.block = block
    return stable.callback
}

public fun ComponentScope.event(block: EventScope.() -> Unit): () -> Unit {
    val key = nextEventKey(SlotKind.UnitEvent)
    val stable = checkedSlot(key, StableUnitEvent::class) { StableUnitEvent(runtime, block) }
    stable.block = block
    return stable.callback
}

private fun KineticaRuntime.reportEffectError(
    key: String,
    errorBoundary: ErrorBoundaryState?,
    error: Throwable,
) {
    if (error is CancellationException) {
        throw error
    }
    if (errorBoundary != null) {
        errorBoundary.capture(this, error)
    } else {
        record(
            JournalKind.BoundaryError,
            "unhandled effect error",
            mapOf("effectKey" to key, "error" to error.toString()),
        )
    }
}
