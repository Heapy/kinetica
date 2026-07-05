package io.heapy.kinetica

internal actual class ReadTrackingLocal actual constructor() {
    private val frames = mutableListOf<(Cell<*>) -> Unit>()

    actual fun current(): ((Cell<*>) -> Unit)? =
        frames.lastOrNull()

    actual fun push(observer: (Cell<*>) -> Unit) {
        frames += observer
    }

    actual fun pop(observer: (Cell<*>) -> Unit) {
        frames.removeLastMatching(observer)
    }

    actual fun clear(): List<(Cell<*>) -> Unit> {
        val previous = frames.toList()
        frames.clear()
        return previous
    }

    actual fun restore(frames: List<(Cell<*>) -> Unit>) {
        this.frames.clear()
        this.frames.addAll(frames)
    }

    // Single-threaded target: a coroutine never migrates threads, so the frame does not need to
    // follow it — the original push/try-finally/pop keeps the observer installed for the block.
    actual suspend fun <T> collectSuspend(
        observer: (Cell<*>) -> Unit,
        block: suspend () -> T,
    ): T {
        push(observer)
        return try {
            block()
        } finally {
            pop(observer)
        }
    }
}

private fun MutableList<(Cell<*>) -> Unit>.removeLastMatching(observer: (Cell<*>) -> Unit) {
    val index = indexOfLast { it === observer }
    if (index >= 0) {
        removeAt(index)
    }
}
