package io.heapy.kinetica

import kotlin.native.concurrent.ThreadLocal

internal actual class ReadTrackingLocal actual constructor() {
    actual fun current(): ((Cell<*>) -> Unit)? =
        NativeReadTrackingFrames.frames.lastOrNull()

    actual fun push(observer: (Cell<*>) -> Unit) {
        NativeReadTrackingFrames.frames += observer
    }

    actual fun pop(observer: (Cell<*>) -> Unit) {
        NativeReadTrackingFrames.frames.removeLastMatching(observer)
    }

    actual fun clear(): List<(Cell<*>) -> Unit> {
        val previous = NativeReadTrackingFrames.frames.toList()
        NativeReadTrackingFrames.frames.clear()
        return previous
    }

    actual fun restore(frames: List<(Cell<*>) -> Unit>) {
        NativeReadTrackingFrames.frames.clear()
        NativeReadTrackingFrames.frames.addAll(frames)
    }

    // The @ThreadLocal frame store is thread-confined and coroutines here are not multiplexed
    // across a dispatcher pool in the runtime's suspend-render path, so the original
    // push/try-finally/pop is retained; a ThreadContextElement is unavailable on this target.
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

@ThreadLocal
private object NativeReadTrackingFrames {
    val frames: MutableList<(Cell<*>) -> Unit> = mutableListOf()
}

private fun MutableList<(Cell<*>) -> Unit>.removeLastMatching(observer: (Cell<*>) -> Unit) {
    val index = indexOfLast { it === observer }
    if (index >= 0) {
        removeAt(index)
    }
}
