package io.heapy.kinetica

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher for [KineticaRuntime.effectScope]. Effect bodies must start in declaration
 * order; backends with a multithreaded Default pool serialize starts, while
 * single-threaded backends already dispatch FIFO and need no wrapper.
 */
internal expect fun serialEffectDispatcher(): CoroutineDispatcher
