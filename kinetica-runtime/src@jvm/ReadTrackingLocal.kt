package io.heapy.kinetica

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

internal actual class ReadTrackingLocal actual constructor() {
    private val frames = ThreadLocal.withInitial { mutableListOf<(Cell<*>) -> Unit>() }

    actual fun current(): ((Cell<*>) -> Unit)? =
        frames.get().lastOrNull()

    actual fun push(observer: (Cell<*>) -> Unit) {
        frames.get() += observer
    }

    actual fun pop(observer: (Cell<*>) -> Unit) {
        frames.get().removeLastMatching(observer)
    }

    actual fun clear(): List<(Cell<*>) -> Unit> {
        val stack = frames.get()
        val previous = stack.toList()
        stack.clear()
        return previous
    }

    actual fun restore(frames: List<(Cell<*>) -> Unit>) {
        val stack = this.frames.get()
        stack.clear()
        stack.addAll(frames)
    }

    actual suspend fun <T> collectSuspend(
        observer: (Cell<*>) -> Unit,
        block: suspend () -> T,
    ): T =
        withContext(ReadTrackingContextElement(this, observer)) {
            block()
        }
}

/** Carries the observer across coroutine thread hops while keeping per-thread stacks balanced. */
private class ReadTrackingContextElement(
    private val local: ReadTrackingLocal,
    private val observer: (Cell<*>) -> Unit,
) : ThreadContextElement<Unit> {
    companion object Key : CoroutineContext.Key<ReadTrackingContextElement>

    override val key: CoroutineContext.Key<*>
        get() = Key

    override fun updateThreadContext(context: CoroutineContext) {
        local.push(observer)
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) {
        local.pop(observer)
    }
}

private fun MutableList<(Cell<*>) -> Unit>.removeLastMatching(observer: (Cell<*>) -> Unit) {
    val index = indexOfLast { it === observer }
    if (index >= 0) {
        removeAt(index)
    }
}
