package io.heapy.kinetica

import kotlin.concurrent.atomics.AtomicInt

@PublishedApi
internal class NativeSpinLock {
    @PublishedApi
    internal val state = AtomicInt(0)

    inline fun <R> synchronized(block: () -> R): R {
        while (!state.compareAndSet(0, 1)) {
            // Spin only on Native. Critical sections are short and allocation-free.
        }
        return try {
            block()
        } finally {
            state.store(0)
        }
    }
}

internal actual fun platformLock(): Any? = NativeSpinLock()

internal actual inline fun <R> synchronizedOn(lock: Any?, block: () -> R): R =
    (lock as NativeSpinLock).synchronized(block)
