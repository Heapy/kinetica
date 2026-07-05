package io.heapy.kinetica

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

internal actual class ReadTrackingLocal actual constructor() {
    private val frames = ThreadLocal.withInitial { mutableListOf<(Cell<*>) -> Unit>() }

    actual fun current(): ((Cell<*>) -> Unit)? =
        stack().lastOrNull()

    actual fun push(observer: (Cell<*>) -> Unit) {
        stack() += observer
    }

    actual fun pop(observer: (Cell<*>) -> Unit) {
        stack().removeLastMatching(observer)
    }

    actual fun clear(): List<(Cell<*>) -> Unit> {
        val stack = stack()
        val previous = stack.toList()
        stack.clear()
        return previous
    }

    actual fun restore(frames: List<(Cell<*>) -> Unit>) {
        val stack = stack()
        stack.clear()
        stack.addAll(frames)
    }

    /**
     * Carries the observer frame with the coroutine via a [ThreadContextElement] so a cell read
     * after a dispatcher/thread hop still finds the observer on the resume thread and the origin
     * thread's stack is left clean. See the JVM implementation for the full rationale.
     */
    actual suspend fun <T> collectSuspend(
        observer: (Cell<*>) -> Unit,
        block: suspend () -> T,
    ): T =
        withContext(ReadTrackingContextElement(this, observer)) {
            block()
        }

    private fun stack(): MutableList<(Cell<*>) -> Unit> =
        frames.get()!!
}

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
