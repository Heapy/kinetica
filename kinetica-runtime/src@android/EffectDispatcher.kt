package io.heapy.kinetica

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Serial view over the shared pool (not a dedicated thread): parallelism is limited,
// not concurrency — a suspended effect does not hold the queue.
internal actual fun serialEffectDispatcher(): CoroutineDispatcher =
    Dispatchers.Default.limitedParallelism(1)
